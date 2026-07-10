package io.quarkus.orbit.pulse;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.orbit.pulse.config.ConfigHelper;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.RepositoryEntity;
import io.quarkus.orbit.pulse.entity.ScoreDetailEntity;
import io.quarkus.orbit.pulse.entity.ScoredPullRequestEntity;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.service.AnalysisService;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@CommandLine.Command(name = "scores", description = "View or refresh scores for a specific repository")
public class ScoresCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(ScoresCommand.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final AnalysisService analysisService;
    private final PrPulseConfig config;

    @CommandLine.Parameters(index = "0", description = "Repository name (e.g. 'smallrye-mutiny' or 'smallrye/smallrye-mutiny')")
    String repo;

    @CommandLine.Option(names = "--refresh", description = "Fetch and re-score PRs from GitHub")
    boolean refresh;

    @CommandLine.Option(names = "--lookback", description = "Override lookback days when refreshing (default from config)")
    Integer lookback;

    @CommandLine.Option(names = "--limit", defaultValue = "20", description = "Max number of PRs to display")
    int limit;

    @CommandLine.Option(names = "--details", description = "Show per-rule score breakdown")
    boolean details;

    public ScoresCommand(AnalysisService analysisService, PrPulseConfig config) {
        this.analysisService = analysisService;
        this.config = config;
    }

    @Override
    @ActivateRequestContext
    public void run() {
        Optional<PrPulseConfig.Repository> repoConfig = ConfigHelper.findRepoConfig(config, repo);
        if (repoConfig.isEmpty()) {
            String known = ConfigHelper.knownRepos(config).stream()
                    .map(r -> "  - " + r)
                    .collect(Collectors.joining("\n"));
            System.err.println("Unknown repository: " + repo);
            System.err.println("Known repositories:\n" + known);
            return;
        }

        PrPulseConfig.Repository config = repoConfig.get();
        String fullName = config.owner() + "/" + config.name();

        if (refresh) {
            System.out.println("Refreshing scores for " + fullName + "...");
            try {
                List<ScoredPullRequest> scored = analysisService.analyzeSingleRepo(repo, true, lookback);
                System.out.printf("Scored %d PRs above threshold.%n%n", scored.size());
            } catch (Exception e) {
                System.err.println("Refresh failed: " + e.getMessage());
                return;
            }
        }

        displayScores(config.owner(), config.name(), fullName);
    }

    void displayScores(String owner, String name, String fullName) {
        QuarkusTransaction.requiringNew().run(() -> {
            Optional<RepositoryEntity> repoEntity = RepositoryEntity.findByOwnerAndName(owner, name);
            if (repoEntity.isEmpty()) {
                System.out.println("No scores stored for " + fullName + ". Use --refresh to fetch and score.");
                return;
            }

            List<ScoredPullRequestEntity> scores = ScoredPullRequestEntity.findByRepo(repoEntity.get(), limit);
            if (scores.isEmpty()) {
                System.out.println("No scores stored for " + fullName + ". Use --refresh to fetch and score.");
                return;
            }

            for (ScoredPullRequestEntity score : scores) {
                score.details.size();
            }

            System.out.printf("Scores for %s (%d PRs scored)%n%n", fullName, scores.size());
            System.out.printf(" %-4s %-8s %-50s %-12s %-8s %-12s%n", "#", "PR", "Title", "Author", "Score", "Date");
            System.out.println("-".repeat(100));

            for (int i = 0; i < scores.size(); i++) {
                ScoredPullRequestEntity score = scores.get(i);
                String title = score.title != null && score.title.length() > 48
                        ? score.title.substring(0, 48) + ".."
                        : score.title;
                System.out.printf(" %-4d #%-7d %-50s @%-11s %-8.1f %s%n",
                        i + 1, score.prNumber, title, score.author, score.totalScore,
                        DATE_FMT.format(score.scoredAt));

                if (details) {
                    for (ScoreDetailEntity detail : score.details) {
                        System.out.printf("       - %-14s %5.1f / 100  (weight %.2f) — %s%n",
                                detail.ruleName + ":", detail.normalizedPoints, detail.weight,
                                detail.reason != null ? detail.reason : "");
                    }
                    System.out.println();
                }
            }

            if (!details) {
                System.out.println("\nUse --details to see per-rule breakdown.");
            }
        });
    }
}
