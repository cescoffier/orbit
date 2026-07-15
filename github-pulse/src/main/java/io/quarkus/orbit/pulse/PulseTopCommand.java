package io.quarkus.orbit.pulse;

import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.service.AnalysisService;
import io.quarkus.orbit.pulse.service.ReportService;
import io.quarkus.picocli.runtime.annotations.TopCommand;
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

@TopCommand
@CommandLine.Command(
        name = "pulse",
        mixinStandardHelpOptions = true,
        subcommands = {AnalyzeCommand.class, ScoresCommand.class, ReleaseCommand.class}
)
public class PulseTopCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(PulseTopCommand.class);

    private final AnalysisService analysisService;
    private final ReportService reportService;

    public PulseTopCommand(AnalysisService analysisService, ReportService reportService) {
        this.analysisService = analysisService;
        this.reportService = reportService;
    }

    @Override
    @ActivateRequestContext
    public void run() {
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
        } catch (Exception e) {
            LOG.error("Analysis failed", e);
            throw new RuntimeException(e);
        }
    }

    private void writeReport(String filename, String content) throws IOException {
        Path reportDir = Path.of("reports");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve(filename), content);
    }
}
