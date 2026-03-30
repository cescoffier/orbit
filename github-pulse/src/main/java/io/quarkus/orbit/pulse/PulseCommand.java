package io.quarkus.orbit.pulse;

import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.service.AnalysisService;
import io.quarkus.orbit.pulse.service.ReportService;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@QuarkusMain
public class PulseCommand implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(PulseCommand.class);

    private final AnalysisService analysisService;
    private final ReportService reportService;

    public PulseCommand(AnalysisService analysisService, ReportService reportService) {
        this.analysisService = analysisService;
        this.reportService = reportService;
    }

    @Override
    @ActivateRequestContext
    public int run(String... args) {
        try {
            LOG.info("Starting GitHub Pulse analysis...");

            Map<String, List<ScoredPullRequest>> results = analysisService.analyzeAll();
            String markdown = reportService.generateMarkdown(results);

            System.out.println(markdown);

            String filename = "Pulse-%s.md".formatted(
                    LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            writeReport(filename, markdown);

            int totalPrs = results.values().stream().mapToInt(List::size).sum();
            LOG.infof("Analysis complete. %d PRs flagged across %d repos. Report: %s",
                    totalPrs, results.size(), filename);

            return 0;
        } catch (Exception e) {
            LOG.error("Analysis failed", e);
            return 1;
        }
    }

    private void writeReport(String filename, String content) throws IOException {
        Path reportDir = Path.of("reports");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve(filename), content);
    }
}
