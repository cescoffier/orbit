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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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


    /**
     * In-memory map of running detection runs
     */
    Map<String, DetectionRun> runningDetections = new ConcurrentHashMap<>();

    /**
     * Start detection asynchronously - returns immediately with run ID
     */
    public DetectionRun startDetection(int lookbackDays) {
        var run = createDetectionRun(lookbackDays);

        // Execute detection in background
        executor.submit(() -> executeDetection(run));

        return run;
    }

    DetectionRun createDetectionRun(int lookbackDays) {
        DetectionRun run = DetectionRun.createNew(lookbackDays);
        runningDetections.put(run.id, run);
        return run;
    }

    /**
     * Execute detection for all configured working groups (runs in background).
     */
    @ActivateRequestContext
    public void executeDetection(DetectionRun run) {
        Log.infof("Starting detection run %s with lookback %d days", run.id, run.lookbackDays);

        try {
            // 1. Fetch working groups
            updateProgress(run.id, "Fetching working groups...", 5);
            List<WorkingGroupBoard> allWorkingGroups = workingGroupRegistry.fetchAllWorkingGroups();

            // 1.1 - Filter out LTS and completed working groups
            List<WorkingGroupBoard> processableWorkingGroups = allWorkingGroups.stream()
                    .filter(WorkingGroupBoard::shouldProcess)
                    .toList();

            // 1.2 - Identify working groups without repository mappings
            List<String> wgsWithoutRepos = processableWorkingGroups.stream()
                    .filter(wg -> !repositoryMapping.repositoryMapping().containsKey(wg.name()))
                    .map(WorkingGroupBoard::name)
                    .toList();

            run.workingGroupsWithoutRepositories.addAll(wgsWithoutRepos);
            if (!wgsWithoutRepos.isEmpty()) {
                Log.warnf("Found %d working groups without repository mappings: %s", wgsWithoutRepos.size(), wgsWithoutRepos);
            }

            // 1.3 - Filter to only working groups with repository mappings
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
            updateProgress(run.id, "Fetching %d  repositories...".formatted(repositories.size()), 7);

            // 3. Fetch issues and PR candidates from each repository. Store them per repository.
            Instant cutoffDate = Instant.now().minus(run.lookbackDays, ChronoUnit.DAYS);
            for (String repository : repositories) {
                this.repositoryService.populate(repository, cutoffDate);
            }

            // 4. Associate each working group with a list of issues / PRs
            Map<WorkingGroupBoard, List<IssueOrPR>> candidatesByWG = workingGroups.stream()
                    .collect(Collectors.toMap(
                            wg -> wg,
                            wg -> {
                                List<String> repos = repositoryMapping.repositoryMapping().get(wg.name());
                                return repos.stream()
                                        .flatMap(repo -> this.repositoryService.get(repo).getIssuesAndPRs().stream())
                                        .collect(Collectors.toList());
                            }
                    ));

            // 5. Process each working group
            int totalWGs = workingGroups.size();
            int processedWGs = 0;
            for (WorkingGroupBoard wg : workingGroups) {
                List<IssueOrPR> issues = candidatesByWG.get(wg);
                Log.infof("Processing WG: %s with %d candidates", wg.name(), issues.size());

                // Filter out items already in the project of the WG
                issues = issues.stream().filter(i -> ! this.workingGroupRegistry.hasItem(wg, i)).toList();

                // Filter out issues with excluded labels (invalid, wontfix, duplicate, etc.)
                issues = issues.stream().filter(i -> !i.hasExcludedLabel()).toList();

                int wgProgress = 10 + (processedWGs * 80 / totalWGs);
                updateProgress(run.id, String.format("Processing %s (%d/%d)", wg.name(), processedWGs + 1, totalWGs), wgProgress);

                Log.infof("Found %d candidates for %s, classifying...", issues.size(), wg.name());

                // Classify with AI
                Map<IssueOrPR, Boolean> classifications = batchClassifier.classifyBatch(
                        wg.proposal(),
                        issues,
                        classificationBatchSize // batch size
                );

                // Compute the final list of candidates for the WG
                List<IssueOrPR> candidates = classifications.entrySet().stream()
                        .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                        .map(Map.Entry::getKey)
                        .toList();
                // Save them in the run
                run.addCandidatesForWorkingGroup(wg.name(), candidates);
                Log.infof("Matched %d/%d candidates for %s", candidates.size(), issues.size(), wg.name());

                processedWGs++;
            }

            // 6. Mark the run as completed
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
