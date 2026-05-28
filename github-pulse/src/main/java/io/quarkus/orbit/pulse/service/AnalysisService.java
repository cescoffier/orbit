package io.quarkus.orbit.pulse.service;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.PrClassification;
import io.quarkus.orbit.pulse.entity.ProcessedPr;
import io.quarkus.orbit.pulse.graphql.GitHubGraphQLClient;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.ScoringEngine;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

@ApplicationScoped
public class AnalysisService {

    private static final Logger LOG = Logger.getLogger(AnalysisService.class);

    private final PrPulseConfig config;
    private final GitHubGraphQLClient graphQLClient;
    private final ScoringEngine scoringEngine;

    public AnalysisService(PrPulseConfig config, GitHubGraphQLClient graphQLClient, ScoringEngine scoringEngine) {
        this.config = config;
        this.graphQLClient = graphQLClient;
        this.scoringEngine = scoringEngine;
    }

    public Map<String, List<ScoredPullRequest>> analyzeAll() throws Exception {
        Map<String, List<ScoredPullRequest>> results = new ConcurrentHashMap<>();
        List<PrPulseConfig.Repository> repos = config.repositories();

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            var tasks = new ArrayList<Map.Entry<PrPulseConfig.Repository, StructuredTaskScope.Subtask<List<ScoredPullRequest>>>>();

            for (PrPulseConfig.Repository repo : repos) {
                var task = scope.fork(() -> analyzeRepo(repo));
                tasks.add(Map.entry(repo, task));
            }

            scope.join();

            for (var entry : tasks) {
                var repo = entry.getKey();
                var subtask = entry.getValue();
                if (subtask.state() == StructuredTaskScope.Subtask.State.FAILED) {
                    LOG.errorf("Analysis failed for %s/%s: %s",
                            repo.owner(), repo.name(), subtask.exception().getMessage());
                    continue;
                }
                List<ScoredPullRequest> scored = subtask.get();
                if (!scored.isEmpty()) {
                    results.put(repo.owner() + "/" + repo.name(), scored);
                }
            }
        }

        return results;
    }

    @ActivateRequestContext
    List<ScoredPullRequest> analyzeRepo(PrPulseConfig.Repository repo) throws Exception {
        LOG.infof("Analyzing %s/%s", repo.owner(), repo.name());

        List<PullRequestData> prs = graphQLClient.fetchMergedPRs(repo.owner(), repo.name());
        LOG.infof("Fetched %d PRs for %s/%s, scoring...", prs.size(), repo.owner(), repo.name());

        List<ScoredPullRequest> scored = scoreInParallel(prs, repo.rules());

        persistResults(scored);

        LOG.infof("Completed %s/%s: %d PRs above threshold", repo.owner(), repo.name(), scored.size());
        return scored;
    }

    List<ScoredPullRequest> scoreInParallel(List<PullRequestData> prs, PrPulseConfig.Rules rules) throws Exception {
        List<PullRequestData> unprocessed = prs.stream()
                .filter(pr -> !isAlreadyProcessed(pr))
                .toList();

        if (unprocessed.isEmpty()) {
            return List.of();
        }

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            var tasks = new ArrayList<StructuredTaskScope.Subtask<ScoredPullRequest>>();

            for (PullRequestData pr : unprocessed) {
                tasks.add(scope.fork(() -> scoreSinglePr(pr, rules)));
            }

            scope.join();

            return tasks.stream()
                    .filter(t -> t.state() == StructuredTaskScope.Subtask.State.SUCCESS)
                    .map(StructuredTaskScope.Subtask::get)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    private ScoredPullRequest scoreSinglePr(PullRequestData pr, PrPulseConfig.Rules rules) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            ScoredPullRequest scored = scoringEngine.score(pr, rules);
            if (scored.score() >= config.globalThreshold()) {
                return scored;
            }
            return null;
        } catch (Exception e) {
            LOG.warnf("Scoring failed for PR #%d in %s: %s",
                    pr.number(), pr.repoIdentifier(), e.getMessage());
            return null;
        } finally {
            requestContext.terminate();
        }
    }

    @Transactional
    boolean isAlreadyProcessed(PullRequestData pr) {
        return ProcessedPr.alreadyProcessed(pr.repoIdentifier(), pr.number());
    }

    @Transactional
    void persistResults(List<ScoredPullRequest> scoredPrs) {
        for (ScoredPullRequest scored : scoredPrs) {
            PullRequestData pr = scored.pr();

            ProcessedPr.markProcessed(pr.repoIdentifier(), pr.number());

            Object categoryObj = scored.metadata().get("category");
            if (categoryObj instanceof PrCategory category) {
                PrClassification.store(pr.repoIdentifier(), pr.number(), category);
            }
        }
    }
}
