package io.quarkus.orbit.pulse.service;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.ProcessedPr;
import io.quarkus.orbit.pulse.graphql.GitHubGraphQLClient;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.ScoringEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        // Phase 1: fetch PRs in parallel
        // Use Map.Entry to pair repos with their fetched PRs without creating a separate class
        List<Map.Entry<PrPulseConfig.Repository, List<PullRequestData>>> fetched = new ArrayList<>();

        try (var fetchScope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            var tasks = new ArrayList<Map.Entry<PrPulseConfig.Repository, StructuredTaskScope.Subtask<List<PullRequestData>>>>();
            for (PrPulseConfig.Repository repo : repos) {
                var task = fetchScope.fork(() -> graphQLClient.fetchMergedPRs(repo.owner(), repo.name()));
                tasks.add(Map.entry(repo, task));
            }
            fetchScope.join();

            for (var entry : tasks) {
                var repo = entry.getKey();
                var subtask = entry.getValue();
                if (subtask.state() == StructuredTaskScope.Subtask.State.FAILED) {
                    LOG.errorf("Failed to fetch PRs for %s/%s: %s",
                            repo.owner(), repo.name(), subtask.exception().getMessage());
                    continue;
                }
                fetched.add(Map.entry(repo, subtask.get()));
            }
        }

        // Phase 2: score PRs in parallel
        try (var scoreScope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            for (var entry : fetched) {
                var repo = entry.getKey();
                var prs = entry.getValue();
                scoreScope.fork(() -> {
                    LOG.infof("Scoring PRs for %s/%s", repo.owner(), repo.name());
                    List<ScoredPullRequest> scored = scoreAndFilter(prs, repo.rules());
                    LOG.infof("Scored PRs for %s/%s", repo.owner(), repo.name());
                    if (!scored.isEmpty()) {
                        results.put(repo.owner() + "/" + repo.name(), scored);
                    }
                    return null;
                });
            }
            scoreScope.join();
        }

        return results;
    }

    @ActivateRequestContext
    List<ScoredPullRequest> scoreAndFilter(List<PullRequestData> prs, PrPulseConfig.Rules rules) {
        List<ScoredPullRequest> result = new ArrayList<>();

        for (PullRequestData pr : prs) {
            if (isAlreadyProcessed(pr)) {
                LOG.debugf("Skipping already processed PR #%d in %s", pr.number(), pr.repoIdentifier());
                continue;
            }

            ScoredPullRequest scored = scoringEngine.score(pr, rules);

            if (scored.score() >= config.globalThreshold()) {
                markAsProcessed(pr);
                result.add(scored);
            }
        }

        return result;
    }

    @Transactional
    boolean isAlreadyProcessed(PullRequestData pr) {
        return ProcessedPr.alreadyProcessed(pr.repoIdentifier(), pr.number());
    }

    @Transactional
    void markAsProcessed(PullRequestData pr) {
        ProcessedPr.markProcessed(pr.repoIdentifier(), pr.number());
    }
}
