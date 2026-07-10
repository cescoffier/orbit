package io.quarkus.orbit.pulse.service;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.PrClassification;
import io.quarkus.orbit.pulse.entity.PullRequestScore;
import io.quarkus.orbit.pulse.entity.RepositoryEntity;
import io.quarkus.orbit.pulse.entity.ScoreDetail;
import io.quarkus.orbit.pulse.graphql.GitHubGraphQLClient;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.ScoringEngine;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    public List<ScoredPullRequest> analyzeSingleRepo(String repoIdentifier) throws Exception {
        PrPulseConfig.Repository repo = findRepoConfig(repoIdentifier)
                .orElseThrow(() -> new IllegalArgumentException("Unknown repository: " + repoIdentifier));
        return analyzeRepo(repo);
    }

    public Optional<PrPulseConfig.Repository> findRepoConfig(String repoIdentifier) {
        return config.repositories().stream()
                .filter(r -> r.name().equals(repoIdentifier)
                        || (r.owner() + "/" + r.name()).equals(repoIdentifier))
                .findFirst();
    }

    @ActivateRequestContext
    List<ScoredPullRequest> analyzeRepo(PrPulseConfig.Repository repo) throws Exception {
        LOG.infof("Analyzing %s/%s", repo.owner(), repo.name());

        List<PullRequestData> prs = graphQLClient.fetchMergedPRs(repo.owner(), repo.name());
        LOG.infof("Fetched %d PRs for %s/%s, scoring...", prs.size(), repo.owner(), repo.name());

        List<ScoredPullRequest> scored = scoreInParallel(prs, repo);

        persistResults(scored);

        LOG.infof("Completed %s/%s: %d PRs above threshold", repo.owner(), repo.name(), scored.size());
        return scored;
    }

    List<ScoredPullRequest> scoreInParallel(List<PullRequestData> prs, PrPulseConfig.Repository repo) throws Exception {
        List<PullRequestData> unprocessed = prs.stream()
                .filter(pr -> !isAlreadyProcessed(pr))
                .toList();

        if (unprocessed.isEmpty()) {
            return List.of();
        }

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            var tasks = new ArrayList<StructuredTaskScope.Subtask<ScoredPullRequest>>();

            for (PullRequestData pr : unprocessed) {
                tasks.add(scope.fork(() -> scoreSinglePr(pr, repo.rules())));
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
        Optional<RepositoryEntity> repo = RepositoryEntity.findByOwnerAndName(pr.repoOwner(), pr.repoName());
        return repo.isPresent() && PullRequestScore.exists(repo.get(), pr.number());
    }

    @Transactional
    void persistResults(List<ScoredPullRequest> scoredPrs) {
        for (ScoredPullRequest scored : scoredPrs) {
            PullRequestData pr = scored.pr();

            RepositoryEntity repo = RepositoryEntity.findOrCreate(pr.repoOwner(), pr.repoName());

            PullRequestScore score = new PullRequestScore();
            score.repository = repo;
            score.prNumber = pr.number();
            score.title = pr.title();
            score.author = pr.author();
            score.url = pr.url();
            score.totalScore = scored.score();
            score.scoredAt = Instant.now();
            score.persist();

            for (ScoringRule.ScoringResult ruleResult : scored.ruleResults()) {
                if (ruleResult.reason() == null) continue;
                ScoreDetail detail = new ScoreDetail();
                detail.pullRequestScore = score;
                detail.ruleName = ruleResult.ruleName();
                detail.points = ruleResult.points();
                detail.normalizedPoints = ruleResult.normalizedPoints();
                detail.weight = weightForRule(ruleResult.ruleName(), scored);
                detail.reason = ruleResult.reason();
                if (!ruleResult.metadata().isEmpty()) {
                    detail.metadata = serializeMetadata(ruleResult.metadata());
                }
                detail.persist();
            }

            Object categoryObj = scored.metadata().get("category");
            if (categoryObj instanceof PrCategory category) {
                PrClassification.store(pr.repoIdentifier(), pr.number(), category);
            }
        }
    }

    private double weightForRule(String ruleName, ScoredPullRequest scored) {
        return switch (ruleName) {
            case "size" -> 0.20;
            case "category" -> 0.35;
            case "critical-path" -> 0.25;
            case "comments" -> 0.20;
            default -> 0.0;
        };
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        for (var entry : metadata.entrySet()) {
            builder.add(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return builder.build().toString();
    }

    public List<String> knownRepos() {
        return config.repositories().stream()
                .map(r -> r.owner() + "/" + r.name())
                .toList();
    }
}
