package io.quarkus.orbit.pulse.service;

import io.quarkus.orbit.pulse.config.ConfigHelper;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.ReleaseEntity;
import io.quarkus.orbit.pulse.entity.RepositoryEntity;
import io.quarkus.orbit.pulse.entity.ScoreDetailEntity;
import io.quarkus.orbit.pulse.entity.ScoredPullRequestEntity;
import io.quarkus.orbit.pulse.graphql.GitHubGraphQLClient;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.ScoringEngine;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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

    public void dryRun(String repoIdentifier, Integer lookbackDays) throws Exception {
        List<PrPulseConfig.Repository> repos;
        if (repoIdentifier != null) {
            repos = List.of(ConfigHelper.findRepoConfig(config, repoIdentifier)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown repository: " + repoIdentifier)));
        } else {
            repos = config.repositories();
        }

        int effectiveLookback = lookbackDays != null ? lookbackDays : config.lookbackDays();
        int totalPrs = 0;

        for (PrPulseConfig.Repository repo : repos) {
            List<PullRequestData> prs = graphQLClient.fetchMergedPRs(repo.owner(), repo.name(), effectiveLookback);
            totalPrs += prs.size();
            LOG.infof("[DRY RUN] %s/%s: %d PRs (lookback=%d days)", repo.owner(), repo.name(), prs.size(), effectiveLookback);
        }

        LOG.infof("[DRY RUN] Total: %d PRs across %d repos", totalPrs, repos.size());
    }

    public Map<String, List<ScoredPullRequest>> analyzeAll(boolean forceRescore, Integer lookbackDays) throws Exception {
        Map<String, List<ScoredPullRequest>> results = new ConcurrentHashMap<>();
        List<PrPulseConfig.Repository> repos = config.repositories();

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            var tasks = new ArrayList<Map.Entry<PrPulseConfig.Repository, StructuredTaskScope.Subtask<List<ScoredPullRequest>>>>();

            for (PrPulseConfig.Repository repo : repos) {
                var task = scope.fork(() -> analyzeRepo(repo, forceRescore, lookbackDays));
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

    public List<ScoredPullRequest> analyzeSingleRepo(String repoIdentifier, boolean forceRescore, Integer lookbackDays) throws Exception {
        PrPulseConfig.Repository repo = ConfigHelper.findRepoConfig(config, repoIdentifier)
                .orElseThrow(() -> new IllegalArgumentException("Unknown repository: " + repoIdentifier));
        return analyzeRepo(repo, forceRescore, lookbackDays);
    }

    @ActivateRequestContext
    List<ScoredPullRequest> analyzeRepo(PrPulseConfig.Repository repo, boolean forceRescore, Integer lookbackDays) throws Exception {
        LOG.infof("Analyzing %s/%s", repo.owner(), repo.name());

        int effectiveLookback = lookbackDays != null ? lookbackDays : config.lookbackDays();
        List<PullRequestData> prs = graphQLClient.fetchMergedPRs(repo.owner(), repo.name(), effectiveLookback);
        LOG.infof("Fetched %d PRs for %s/%s, scoring...", prs.size(), repo.owner(), repo.name());

        List<ScoredPullRequest> allScored = scoreInParallel(prs, repo, forceRescore);
        persistResults(allScored, repo);

        List<ScoredPullRequest> aboveThreshold = allScored.stream()
                .filter(s -> s.score() >= config.globalThreshold())
                .toList();

        LOG.infof("Completed %s/%s: %d scored, %d above threshold",
                repo.owner(), repo.name(), allScored.size(), aboveThreshold.size());
        return aboveThreshold;
    }

    public List<ScoredPullRequest> scoreInParallel(List<PullRequestData> prs, PrPulseConfig.Repository repo, boolean forceRescore) throws Exception {
        List<PullRequestData> toProcess;
        if (forceRescore) {
            toProcess = prs;
        } else {
            toProcess = prs.stream().filter(pr -> !isAlreadyProcessed(pr)).toList();
        }

        // Remove dependabot
        toProcess = toProcess.stream().filter(pr -> ! pr.author().equalsIgnoreCase("dependabot")).toList();

        if (toProcess.isEmpty()) {
            return List.of();
        }

        CountDownLatch latch = new CountDownLatch(toProcess.size());
        List<ScoredPullRequest> result = new CopyOnWriteArrayList<>();
        for (PullRequestData pr : toProcess) {
            Thread.ofVirtual().start(() -> {
                try {
                    ScoredPullRequest request = scoreSinglePr(pr, repo.rules());
                    if (request != null) {
                        result.add(request);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        return result;
    }


    public ScoredPullRequest scoreSinglePr(PullRequestData pr, PrPulseConfig.Rules rules) {
        try {
            ScoredPullRequest scored = scoringEngine.score(pr, rules);
            LOG.infof("PR #%d: score=%.2f, title=%s",
                    scored.pr().number(), scored.score(), scored.pr().title());
            return scored;
        } catch (Exception e) {
            LOG.warnf("Scoring failed for PR #%d in %s: %s",
                    pr.number(), pr.repoIdentifier(), e.getMessage());
            return null;
        }
    }

    public List<ScoredPullRequest> loadCachedScores(String owner, String repoName, List<Integer> prNumbers) {
        Optional<RepositoryEntity> repoOpt = RepositoryEntity.findByOwnerAndName(owner, repoName);
        if (repoOpt.isEmpty()) return List.of();

        RepositoryEntity repo = repoOpt.get();
        List<ScoredPullRequest> cached = new ArrayList<>();
        for (int prNumber : prNumbers) {
            ScoredPullRequestEntity.findByRepoAndNumber(repo, prNumber).ifPresent(entity -> {
                List<ScoringRule.ScoringResult> ruleResults = entity.details.stream()
                        .map(d -> new ScoringRule.ScoringResult(d.ruleName, d.points, d.normalizedPoints, d.reason))
                        .toList();
                var pr = new PullRequestData(owner, repoName, entity.prNumber,
                        entity.title, entity.url, entity.author, "", 0, 0, 0, List.of(), List.of());
                cached.add(new ScoredPullRequest(pr, entity.totalScore, ruleResults, Map.of(), entity.category, entity.summary));
            });
        }
        return cached;
    }

    boolean isAlreadyProcessed(PullRequestData pr) {
        Optional<RepositoryEntity> repo = RepositoryEntity.findByOwnerAndName(pr.repoOwner(), pr.repoName());
        return repo.isPresent() && ScoredPullRequestEntity.exists(repo.get(), pr.number());
    }

    @Transactional
    public void persistResults(List<ScoredPullRequest> scoredPrs, PrPulseConfig.Repository repoConfig) {
        for (ScoredPullRequest scored : scoredPrs) {
            PullRequestData pr = scored.pr();

            List<String> artifacts = repoConfig.artifacts().orElse(List.of());
            RepositoryEntity repo = RepositoryEntity.findByOwnerAndName(pr.repoOwner(), pr.repoName())
                    .orElseGet(() -> {
                        RepositoryEntity newRepo = new RepositoryEntity();
                        newRepo.owner = pr.repoOwner();
                        newRepo.name = pr.repoName();
                        newRepo.source = repoConfig.source();
                        newRepo.artifacts = artifacts;
                        newRepo.persist();
                        return newRepo;
                    });

            ScoredPullRequestEntity spr = ScoredPullRequestEntity.findByRepoAndNumber(repo, pr.number())
                    .orElseGet(ScoredPullRequestEntity::new);

            spr.repository = repo;
            spr.prNumber = pr.number();
            spr.title = pr.title();
            spr.author = pr.author();
            spr.url = pr.url();
            spr.totalScore = scored.score();
            spr.scoredAt = Instant.now();
            spr.category = scored.category();
            spr.summary = scored.summary();
            spr.persist();

            if (!spr.details.isEmpty()) {
                spr.details.clear();
                ScoredPullRequestEntity.flush();
            }

            for (ScoringRule.ScoringResult ruleResult : scored.ruleResults()) {
                ScoreDetailEntity detail = new ScoreDetailEntity();
                detail.scoredPullRequest = spr;
                detail.ruleName = ruleResult.ruleName();
                detail.points = ruleResult.points();
                detail.normalizedPoints = ruleResult.normalizedPoints();
                detail.weight = scoringEngine.weightForRule(ruleResult.ruleName(), repoConfig.rules());
                detail.reason = ruleResult.reason();
                if (!ruleResult.metadata().isEmpty()) {
                    detail.metadata = serializeMetadata(ruleResult.metadata());
                }
                detail.persist();
            }
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        for (var entry : metadata.entrySet()) {
            builder.add(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return builder.build().toString();
    }

    public List<ScoredPullRequest> loadReleasePrs(String owner, String repoName, String tag) {
        Optional<RepositoryEntity> repoOpt = RepositoryEntity.findByOwnerAndName(owner, repoName);
        if (repoOpt.isEmpty()) return List.of();

        RepositoryEntity repo = repoOpt.get();
        Optional<ReleaseEntity> releaseOpt = ReleaseEntity.findByRepoAndTag(repo, tag);
        if (releaseOpt.isEmpty()) return List.of();

        ReleaseEntity release = releaseOpt.get();
        List<ScoredPullRequest> result = new ArrayList<>();
        for (ScoredPullRequestEntity entity : release.pullRequests) {
            List<ScoringRule.ScoringResult> ruleResults = entity.details.stream()
                    .map(d -> new ScoringRule.ScoringResult(d.ruleName, d.points, d.normalizedPoints, d.reason))
                    .toList();
            var pr = new PullRequestData(owner, repoName, entity.prNumber,
                    entity.title, entity.url, entity.author, "", 0, 0, 0, List.of(), List.of());
            result.add(new ScoredPullRequest(pr, entity.totalScore, ruleResults, Map.of(), entity.category, entity.summary));
        }
        return result;
    }

    @Transactional
    public void associateWithRelease(String owner, String repoName, String tag,
                                      List<ScoredPullRequest> scoredPrs) {
        Optional<RepositoryEntity> repoOpt = RepositoryEntity.findByOwnerAndName(owner, repoName);
        if (repoOpt.isEmpty()) {
            LOG.warnf("Cannot associate release %s/%s %s: repository not found", owner, repoName, tag);
            return;
        }
        RepositoryEntity repo = repoOpt.get();

        ReleaseEntity release = ReleaseEntity.findByRepoAndTag(repo, tag)
                .orElseGet(() -> {
                    ReleaseEntity r = new ReleaseEntity();
                    r.repository = repo;
                    r.tag = tag;
                    return r;
                });
        release.analyzedAt = Instant.now();
        release.persist();

        for (ScoredPullRequest scored : scoredPrs) {
            ScoredPullRequestEntity.findByRepoAndNumber(repo, scored.pr().number())
                    .ifPresent(release.pullRequests::add);
        }
    }
}
