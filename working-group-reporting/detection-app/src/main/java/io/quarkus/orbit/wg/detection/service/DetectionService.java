package io.quarkus.orbit.wg.detection.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import io.quarkus.orbit.wg.detection.config.RepositoryMapping;
import io.quarkus.orbit.wg.detection.model.DetectionRun;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;
import io.quarkus.orbit.wg.detection.model.WorkingGroupBoard;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class DetectionService {

    @Inject
    WorkingGroupService workingGroupRegistry;

    @Inject
    GitHubRepositoryService repositoryService;

    @Inject
    BatchClassifier batchClassifier;

    @Inject
    RepositoryMapping repositoryMapping;

    @Inject
    ManagedExecutor executor;

    @ConfigProperty(name = "detection.batch-size", defaultValue = "50")
    int classificationBatchSize;

    Map<String, DetectionRun> runningDetections = new ConcurrentHashMap<>();

    public DetectionRun startDetection(int lookbackDays) {
        var run = createDetectionRun(lookbackDays);
        executor.submit(() -> executeDetection(run));
        return run;
    }

    DetectionRun createDetectionRun(int lookbackDays) {
        DetectionRun run = DetectionRun.createNew(lookbackDays);
        runningDetections.put(run.id, run);
        return run;
    }

    @ActivateRequestContext
    public void executeDetection(DetectionRun run) {
        Log.infof("Starting detection run %s with lookback %d days", run.id, run.lookbackDays);

        try {
            // 1. Fetch working groups
            updateProgress(run.id, "Fetching working groups...", 5);
            List<WorkingGroupBoard> allWorkingGroups = workingGroupRegistry.fetchAllWorkingGroups();

            List<WorkingGroupBoard> processableWorkingGroups = allWorkingGroups.stream()
                    .filter(WorkingGroupBoard::shouldProcess)
                    .toList();

            List<String> wgsWithoutRepos = processableWorkingGroups.stream()
                    .filter(wg -> !repositoryMapping.repositoryMapping().containsKey(wg.name()))
                    .map(WorkingGroupBoard::name)
                    .toList();

            run.workingGroupsWithoutRepositories.addAll(wgsWithoutRepos);
            if (!wgsWithoutRepos.isEmpty()) {
                Log.warnf("Found %d working groups without repository mappings: %s", wgsWithoutRepos.size(), wgsWithoutRepos);
            }

            List<WorkingGroupBoard> workingGroups = processableWorkingGroups.stream()
                    .filter(wg -> repositoryMapping.repositoryMapping().containsKey(wg.name()))
                    .toList();

            Log.infof("Found %d processable working groups with repository mappings", workingGroups.size());
            updateProgress(run.id, "Found %d processable working groups...".formatted(workingGroups.size()), 6);

            // 2. Extract the set of repositories to process
            Set<String> repositories = workingGroups.stream()
                    .map(wg -> repositoryMapping.repositoryMapping().get(wg.name()))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
            Log.infof("Found %d repositories to analyze: %s", repositories.size(), repositories);
            updateProgress(run.id, "Fetching %d repositories...".formatted(repositories.size()), 7);

            // 3. Fetch issues and PR candidates from each repository
            Instant cutoffDate = Instant.now().minus(run.lookbackDays, ChronoUnit.DAYS);
            for (String repository : repositories) {
                this.repositoryService.populate(repository, cutoffDate);
            }

            // 4. Build the candidate pool per WG and filter
            Map<String, List<IssueOrPR>> candidatesByWG = new LinkedHashMap<>();
            Map<String, String> wgProposals = new LinkedHashMap<>();
            for (WorkingGroupBoard wg : workingGroups) {
                List<String> repos = repositoryMapping.repositoryMapping().get(wg.name());
                List<IssueOrPR> issues = repos.stream()
                        .flatMap(repo -> this.repositoryService.get(repo).getIssuesAndPRs().stream())
                        .collect(Collectors.toList());

                // Filter out items already in the WG project
                issues = issues.stream().filter(i -> !this.workingGroupRegistry.hasItem(wg, i)).toList();
                // Filter out issues with excluded labels
                issues = issues.stream().filter(i -> !i.hasExcludedLabel()).toList();

                candidatesByWG.put(wg.name(), issues);
                wgProposals.put(wg.name(), wg.proposal());
            }

            // 5. Build deduplicated candidate pool for multi-WG classification
            //    Each unique issue is classified against ALL its candidate WGs at once,
            //    so the AI can pick the single best match instead of saying "yes" to multiple.
            updateProgress(run.id, "Preparing candidates for classification...", 15);

            Set<IssueOrPR> allCandidates = new LinkedHashSet<>();
            for (List<IssueOrPR> issues : candidatesByWG.values()) {
                allCandidates.addAll(issues);
            }

            // For each candidate, determine which WGs it could belong to
            Map<IssueOrPR, Set<String>> candidateToWGs = new LinkedHashMap<>();
            for (IssueOrPR candidate : allCandidates) {
                Set<String> wgs = new LinkedHashSet<>();
                for (Map.Entry<String, List<IssueOrPR>> entry : candidatesByWG.entrySet()) {
                    if (entry.getValue().contains(candidate)) {
                        wgs.add(entry.getKey());
                    }
                }
                candidateToWGs.put(candidate, wgs);
            }

            Log.infof("Total unique candidates: %d across %d WGs", allCandidates.size(), workingGroups.size());
            updateProgress(run.id, "Classifying %d candidates against %d WGs...".formatted(
                    allCandidates.size(), workingGroups.size()), 20);

            // 6. Classify all candidates at once against all relevant WGs
            //    The AI sees all WG proposals and picks the single best match for each issue
            Map<String, String> relevantProposals = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : wgProposals.entrySet()) {
                if (candidatesByWG.get(entry.getKey()) != null && !candidatesByWG.get(entry.getKey()).isEmpty()) {
                    relevantProposals.put(entry.getKey(), entry.getValue());
                }
            }

            Map<String, List<IssueOrPR>> classificationResults = batchClassifier.classifyBatch(
                    relevantProposals,
                    new ArrayList<>(allCandidates),
                    classificationBatchSize
            );

            updateProgress(run.id, "Processing classification results...", 90);

            // 7. Store results - only keep matches where the issue was actually a candidate for that WG
            for (Map.Entry<String, List<IssueOrPR>> entry : classificationResults.entrySet()) {
                String wgName = entry.getKey();
                Set<IssueOrPR> validCandidatesForWG = new HashSet<>(candidatesByWG.getOrDefault(wgName, List.of()));

                List<IssueOrPR> validMatches = entry.getValue().stream()
                        .filter(validCandidatesForWG::contains)
                        .toList();

                run.addCandidatesForWorkingGroup(wgName, validMatches);
                Log.infof("Matched %d candidates for %s", validMatches.size(), wgName);
            }

            // 8. Mark the run as completed
            updateProgress(run.id, "Finalizing results...", 95);
            finalizeRun(run.id);
            updateProgress(run.id, "Completed", 100);

            Log.infof("Detection run %s completed", run.id);

        } catch (Exception e) {
            Log.errorf(e, "Detection run %s failed", run.id);
            updateProgress(run.id, "Failed: " + e.getMessage(), 0);
            markRunFailed(run.id, e.getMessage());
        }
    }

    public void updateProgress(String runId, String message, int percent) {
        DetectionRun run = runningDetections.get(runId);
        if (run != null) {
            run.updateProgress(message, percent);
        }
    }

    public void finalizeRun(String runId) {
        DetectionRun run = runningDetections.get(runId);
        if (run != null) {
            run.markCompleted();
        }
    }

    public void markRunFailed(String runId, String errorMessage) {
        DetectionRun run = runningDetections.get(runId);
        if (run != null) {
            run.markFailed(errorMessage);
        }
    }

    public DetectionRun getDetectionRun(String runId) {
        return runningDetections.get(runId);
    }

}
