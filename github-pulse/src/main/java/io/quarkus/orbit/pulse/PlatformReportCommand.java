package io.quarkus.orbit.pulse;

import io.quarkus.orbit.pulse.config.ConfigHelper;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.ReleaseEntity;
import io.quarkus.orbit.pulse.entity.RepositoryEntity;
import io.quarkus.orbit.pulse.entity.RepositorySource;
import io.quarkus.orbit.pulse.entity.ScoredPullRequestEntity;
import io.quarkus.orbit.pulse.model.PlatformReportInput;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import io.quarkus.orbit.pulse.service.ReportService;
import io.quarkus.orbit.pulse.service.ReportService.PlatformRepoReport;
import io.quarkus.orbit.pulse.service.ReportService.PrWithVersions;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@CommandLine.Command(name = "platform-report", description = "Generate a platform release report from the database")
public class PlatformReportCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(PlatformReportCommand.class);

    private final PrPulseConfig config;
    private final ReportService reportService;

    @CommandLine.Parameters(index = "0", description = "Path to the platform YAML file")
    Path yamlFile;

    public PlatformReportCommand(PrPulseConfig config, ReportService reportService) {
        this.config = config;
        this.reportService = reportService;
    }

    @Override
    @ActivateRequestContext
    public void run() {
        try {
            PlatformReportInput input = PlatformReportInput.fromYaml(yamlFile);
            LOG.infof("Loaded platform report input: %d quarkus versions, %d repos",
                    input.quarkusVersions().size(), input.releases().size());

            // Build repo -> tags map
            Map<PrPulseConfig.Repository, List<String>> repoTags = resolveRepoTags(input);

            int threshold = config.globalThreshold();

            // Collect data grouped by source -> repo name -> report
            Map<RepositorySource, Map<String, PlatformRepoReport>> sections = new LinkedHashMap<>();

            for (var entry : repoTags.entrySet()) {
                PrPulseConfig.Repository repoConfig = entry.getKey();
                List<String> tags = entry.getValue();
                String owner = repoConfig.owner();
                String repoName = repoConfig.name();

                RepositoryEntity repoEntity = RepositoryEntity.findByOwnerAndName(owner, repoName)
                        .orElseThrow(() -> new IllegalStateException(
                                "Repository %s/%s not found in database. Run: just release-report %s <tag>"
                                        .formatted(owner, repoName, repoName)));

                // Load PRs from all releases, tracking which versions each PR belongs to
                Map<Integer, List<String>> prVersions = new LinkedHashMap<>();
                Map<Integer, ScoredPullRequestEntity> prEntities = new LinkedHashMap<>();

                for (String tag : tags) {
                    ReleaseEntity release = ReleaseEntity.findByRepoAndTag(repoEntity, tag)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Release %s/%s %s not found in database. Run: just release-report %s %s"
                                            .formatted(owner, repoName, tag, repoName, tag)));

                    for (ScoredPullRequestEntity prEntity : release.pullRequests) {
                        prVersions.computeIfAbsent(prEntity.prNumber, k -> new ArrayList<>()).add(tag);
                        prEntities.putIfAbsent(prEntity.prNumber, prEntity);
                    }
                }

                // Convert to model, filter by threshold, sort by score desc
                List<PrWithVersions> prsWithVersions = prEntities.values().stream()
                        .filter(e -> e.totalScore >= threshold)
                        .sorted(Comparator.comparingDouble((ScoredPullRequestEntity e) -> e.totalScore).reversed())
                        .map(entity -> {
                            List<ScoringRule.ScoringResult> ruleResults = entity.details.stream()
                                    .map(d -> new ScoringRule.ScoringResult(d.ruleName, d.points, d.normalizedPoints, d.reason))
                                    .toList();
                            var pr = new PullRequestData(owner, repoName, entity.prNumber,
                                    entity.title, entity.url, entity.author, "", 0, 0, 0, List.of(), List.of());
                            var scored = new ScoredPullRequest(pr, entity.totalScore, ruleResults, Map.of(), entity.category, entity.summary);
                            return new PrWithVersions(scored, prVersions.get(entity.prNumber));
                        })
                        .toList();

                if (prsWithVersions.isEmpty()) continue;

                RepositorySource source = repoConfig.source();
                sections.computeIfAbsent(source, k -> new TreeMap<>())
                        .put(repoName, new PlatformRepoReport(tags, prsWithVersions));
            }

            // Generate report
            String title = input.quarkusVersions().isEmpty()
                    ? "Platform Release"
                    : "Quarkus " + String.join(", ", input.quarkusVersions());
            String markdown = reportService.generatePlatformReportMarkdown(title, sections);
            System.out.println(markdown);

            String lastVersion = input.quarkusVersions().isEmpty()
                    ? "custom"
                    : input.quarkusVersions().getLast();
            String filename = "PlatformReport-%s.md".formatted(lastVersion);
            reportService.writeReport(filename, markdown);

            LOG.infof("Platform report complete. Report: reports/%s", filename);
        } catch (Exception e) {
            LOG.error("Platform report failed", e);
            throw new RuntimeException(e);
        }
    }

    private Map<PrPulseConfig.Repository, List<String>> resolveRepoTags(PlatformReportInput input) {
        Map<PrPulseConfig.Repository, List<String>> result = new LinkedHashMap<>();

        // Map quarkus-versions to the main quarkus repo only
        if (!input.quarkusVersions().isEmpty()) {
            ConfigHelper.findRepoConfig(config, "quarkus")
                    .ifPresent(repo -> result.put(repo, new ArrayList<>(input.quarkusVersions())));
        }

        // Map explicit releases
        for (var entry : input.releases().entrySet()) {
            String repoName = entry.getKey();
            List<String> tags = entry.getValue();
            PrPulseConfig.Repository repoConfig = ConfigHelper.findRepoConfig(config, repoName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown repository '%s'. Check application.yaml for configured repositories."
                                    .formatted(repoName)));
            result.put(repoConfig, tags);
        }

        return result;
    }
}
