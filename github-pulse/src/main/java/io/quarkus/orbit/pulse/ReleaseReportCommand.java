package io.quarkus.orbit.pulse;

import io.quarkus.orbit.pulse.config.ConfigHelper;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.config.PrPulseConfig.ReleaseStrategy;
import io.quarkus.orbit.pulse.graphql.GitHubGraphQLClient;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.release.CommitGraphReleaseStrategy;
import io.quarkus.orbit.pulse.release.ReleaseNotesParser;
import io.quarkus.orbit.pulse.service.AnalysisService;
import io.quarkus.orbit.pulse.service.ReportService;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@CommandLine.Command(name = "release-report", description = "Analyze PRs in a single GitHub release")
public class ReleaseReportCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(ReleaseReportCommand.class);

    private final PrPulseConfig config;
    private final GitHubGraphQLClient graphQLClient;
    private final CommitGraphReleaseStrategy commitGraphStrategy;
    private final AnalysisService analysisService;
    private final ReportService reportService;

    @CommandLine.Parameters(index = "0", description = "Repository (e.g. 'quarkus' or 'quarkusio/quarkus')")
    String repo;

    @CommandLine.Parameters(index = "1", description = "Release tag (e.g. '3.18.0')")
    String tag;

    @CommandLine.Option(names = "--refresh", description = "Force re-scoring of already-processed PRs")
    boolean refresh;

    @CommandLine.Option(names = "--details", description = "Include per-rule scoring breakdown in the report")
    boolean details;

    public ReleaseReportCommand(PrPulseConfig config, GitHubGraphQLClient graphQLClient,
                                CommitGraphReleaseStrategy commitGraphStrategy,
                                AnalysisService analysisService, ReportService reportService) {
        this.config = config;
        this.graphQLClient = graphQLClient;
        this.commitGraphStrategy = commitGraphStrategy;
        this.analysisService = analysisService;
        this.reportService = reportService;
    }

    @Override
    @ActivateRequestContext
    public void run() {
        try {
            PrPulseConfig.Repository repoConfig = ConfigHelper.findRepoConfig(config, repo)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown repository: %s. Known repos: %s".formatted(repo, ConfigHelper.knownRepos(config))));

            String owner = repoConfig.owner();
            String repoName = repoConfig.name();
            String repoId = owner + "/" + repoName;

            ReleaseStrategy strategy = repoConfig.releaseStrategy();
            LOG.infof("Generating release report for %s %s (strategy: %s)...", repoId, tag, strategy);

            List<Integer> prNumbers = List.of();

            if (strategy == ReleaseStrategy.COMMIT_GRAPH) {
                // Commit graph: walk tags for repos where it's fast enough
                try {
                    String previousTag = graphQLClient.fetchPreviousReleaseTag(owner, repoName, tag);
                    if (previousTag != null) {
                        LOG.infof("Resolved previous release: %s, walking commit graph %s..%s", previousTag, previousTag, tag);
                        prNumbers = commitGraphStrategy.fetchPRsBetweenTags(owner, repoName, previousTag, tag);
                        if (!prNumbers.isEmpty()) {
                            LOG.infof("Commit graph found %d PRs between %s..%s", prNumbers.size(), previousTag, tag);
                        }
                    } else {
                        LOG.infof("No previous release found for %s %s, skipping commit graph", repoId, tag);
                    }
                } catch (Exception e) {
                    LOG.warnf("Commit graph strategy failed for %s %s: %s, falling back to release notes",
                            repoId, tag, e.getMessage());
                }
            }

            // Release notes parsing: primary for RELEASE_NOTES repos, fallback for COMMIT_GRAPH
            if (prNumbers.isEmpty()) {
                if (strategy == ReleaseStrategy.COMMIT_GRAPH) {
                    LOG.infof("Falling back to release notes parsing for %s %s...", repoId, tag);
                } else {
                    LOG.infof("Using release notes parsing for %s %s...", repoId, tag);
                }
                String releaseBody = graphQLClient.fetchReleaseBody(owner, repoName, tag);
                prNumbers = ReleaseNotesParser.parsePrNumbers(releaseBody);
                if (prNumbers.isEmpty()) {
                    LOG.warnf("No PRs found for %s %s", repoId, tag);
                    System.out.println(reportService.generateReleaseReportMarkdown(repoId, tag, List.of()));
                    return;
                }
                LOG.infof("Release notes parsing found %d PRs for %s %s", prNumbers.size(), repoId, tag);
            }

            // Enrich, score, report
            LOG.infof("Enriching %d PRs...", prNumbers.size());
            List<PullRequestData> prs = graphQLClient.fetchPullRequestsByNumbers(owner, repoName, prNumbers);

            LOG.infof("Scoring %d PRs...", prs.size());
            List<ScoredPullRequest> newlyScored = analysisService.scoreInParallel(prs, repoConfig, refresh)
                    .stream().filter(Objects::nonNull).toList();
            analysisService.persistResults(newlyScored, repoConfig);

            Set<Integer> newlyProcessed = newlyScored.stream()
                    .map(s -> s.pr().number()).collect(Collectors.toSet());
            List<Integer> cachedNumbers = prNumbers.stream()
                    .filter(n -> !newlyProcessed.contains(n)).toList();
            List<ScoredPullRequest> cached = analysisService.loadCachedScores(owner, repoName, cachedNumbers);

            List<ScoredPullRequest> allScored = new ArrayList<>(newlyScored);
            allScored.addAll(cached);
            LOG.infof("%d newly scored, %d from cache", newlyScored.size(), cached.size());

            analysisService.associateWithRelease(owner, repoName, tag, allScored);

            // Include PRs previously associated with this release but not in the current discovery
            List<ScoredPullRequest> previouslyAssociated = analysisService.loadReleasePrs(owner, repoName, tag);
            Set<Integer> currentPrNumbers = allScored.stream()
                    .map(s -> s.pr().number()).collect(Collectors.toSet());
            for (ScoredPullRequest rp : previouslyAssociated) {
                if (!currentPrNumbers.contains(rp.pr().number())) {
                    allScored.add(rp);
                }
            }

            int threshold = config.globalThreshold();
            List<ScoredPullRequest> scored = allScored.stream()
                    .filter(s -> s.score() >= threshold)
                    .toList();
            LOG.infof("%d PRs scored, %d above threshold (%d)", allScored.size(), scored.size(), threshold);

            String markdown = reportService.generateReleaseReportMarkdown(repoId, tag, scored, details);
            System.out.println(markdown);

            String filename = "ReleaseReport-%s-%s.md".formatted(repoName, tag);
            reportService.writeReport(filename, markdown);

            LOG.infof("Release report complete. %d PRs scored. Report: reports/%s", scored.size(), filename);
        } catch (Exception e) {
            LOG.error("Release report failed", e);
            throw new RuntimeException(e);
        }
    }
}
