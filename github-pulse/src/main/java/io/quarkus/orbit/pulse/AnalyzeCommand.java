package io.quarkus.orbit.pulse;

import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.service.AnalysisService;
import io.quarkus.orbit.pulse.service.ReportService;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@CommandLine.Command(name = "analyze", description = "Run analysis across all or one repo and generate report")
public class AnalyzeCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(AnalyzeCommand.class);

    private final AnalysisService analysisService;
    private final ReportService reportService;

    @CommandLine.Parameters(index = "0", arity = "0..1",
            description = "Optional repository (e.g. 'smallrye-mutiny' or 'smallrye/smallrye-mutiny')")
    String repo;

    @CommandLine.Option(names = "--lookback", description = "Override lookback days (default from config)")
    Integer lookback;

    @CommandLine.Option(names = "--refresh", description = "Force re-scoring of already-processed PRs")
    boolean refresh;

    @CommandLine.Option(names = "--dry-run", description = "Fetch PRs without scoring or generating a report")
    boolean dryRun;

    public AnalyzeCommand(AnalysisService analysisService, ReportService reportService) {
        this.analysisService = analysisService;
        this.reportService = reportService;
    }

    @Override
    @ActivateRequestContext
    public void run() {
        try {
            if (dryRun) {
                analysisService.dryRun(repo, lookback);
                return;
            }

            Map<String, List<ScoredPullRequest>> results;

            if (repo != null) {
                LOG.infof("Analyzing single repo: %s", repo);
                List<ScoredPullRequest> scored = analysisService.analyzeSingleRepo(repo, refresh, lookback);
                results = scored.isEmpty() ? Map.of() : Map.of(repo, scored);
            } else {
                LOG.info("Starting GitHub Pulse analysis...");
                results = analysisService.analyzeAll(refresh, lookback);
            }

            String markdown = reportService.generateMarkdown(results);

            System.out.println(markdown);

            String filename = "Pulse-%s.md".formatted(
                    LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            reportService.writeReport(filename, markdown);

            int totalPrs = results.values().stream().mapToInt(List::size).sum();
            LOG.infof("Analysis complete. %d PRs flagged across %d repos. Report: %s",
                    totalPrs, results.size(), filename);
        } catch (Exception e) {
            LOG.error("Analysis failed", e);
            throw new RuntimeException(e);
        }
    }
}
