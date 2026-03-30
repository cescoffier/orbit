package io.quarkus.orbit.monday.command;

import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.config.MondayReportConfig;
import io.quarkus.orbit.monday.service.GitHubScanService;
import io.quarkus.orbit.monday.service.ReportService;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("preview")
@Command(name = "report", mixinStandardHelpOptions = true, version = "2.0",
        description = "Monday Morning Executive Briefing - Generate weekly GitHub activity reports")
public class MondayIntelligenceReportCommand implements Runnable {

    @Option(names = {"--output", "-o"}, description = "Output directory for the report (default: configured output dir)")
    String outputDir;

    @Inject
    GitHubScanService scanService;

    @Inject
    ReportService reportService;

    @Inject
    MondayReportConfig config;

    @Override
    public void run() {
        try {
            long start = System.currentTimeMillis();
            Log.info("Starting Monday Morning Executive Briefing...");

            // Calculate previous calendar week (Monday to Sunday)
            LocalDate today = LocalDate.now();
            LocalDate lastMonday = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
            LocalDate lastSunday = lastMonday.plusDays(6);

            Log.infof("Analyzing week: %s to %s", lastMonday, lastSunday);

            List<io.quarkus.orbit.monday.service.RepoActivity> repoActivities = new ArrayList<>();
            for (String repo : config.repositories()) {
                repoActivities.add(scanService.scanRepository(repo, lastMonday, lastSunday));
            }

            Log.infof("Data Fetched in %dms", (System.currentTimeMillis() - start));

            String report = reportService.generateAIReport(repoActivities, lastMonday, lastSunday);

            reportService.saveReport(report, lastMonday, outputDir);

        } catch (Exception e) {
            Log.error("Failed to generate report", e);
            throw new RuntimeException(e);
        }
    }
}
