package io.quarkus.orbit.monday.command;

import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.service.heatmap.HeatmapService;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.LocalDate;
import java.time.YearMonth;

@Command(name = "heatmap", mixinStandardHelpOptions = true, version = "1.0",
        description = "Generate Community Activity Heatmap - Track acceleration across Quarkus areas and Quarkiverse extensions")
public class HeatmapCommand implements Runnable {

    @Option(names = {"--output"}, description = "Output file path (default: configured output dir)")
    String outputPath;

    @Inject
    HeatmapService heatmapService;

    @Override
    public void run() {
        try {
            long start = System.currentTimeMillis();
            Log.info("Starting Community Heatmap Generation (Java " + System.getProperty("java.version") + ")...");

            // Calculate time windows - current month and previous month
            LocalDate now = LocalDate.now();
            YearMonth currentMonth = YearMonth.from(now).minusMonths(1); // Last completed month
            YearMonth previousMonth = currentMonth.minusMonths(1);

            Log.infof("Analyzing acceleration:");
            Log.infof("   Current period: %s (from %s to %s)", currentMonth, currentMonth.atDay(1), currentMonth.atEndOfMonth());
            Log.infof("   Previous period: %s (from %s to %s)", previousMonth, previousMonth.atDay(1), previousMonth.atEndOfMonth());

            // Generate heatmap data
            String csvData = heatmapService.generateHeatmapData(currentMonth, previousMonth);

            Log.infof("Data collected in %dms", (System.currentTimeMillis() - start));

            // Generate AI analysis
            String report = heatmapService.generateHeatmapReport(csvData, currentMonth, previousMonth);

            // Save report
            String outputFile = heatmapService.saveReport(report, currentMonth, outputPath);

            Log.infof("Heatmap report generated in %dms", (System.currentTimeMillis() - start));
            Log.infof("Report saved to: %s", outputFile);

        } catch (Exception e) {
            Log.error("Failed to generate heatmap", e);
            throw new RuntimeException(e);
        }
    }
}
