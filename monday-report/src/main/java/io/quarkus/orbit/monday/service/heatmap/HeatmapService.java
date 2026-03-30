package io.quarkus.orbit.monday.service.heatmap;

import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.config.MondayReportConfig;
import io.quarkus.orbit.monday.service.support.ActivityMetrics;
import io.quarkus.orbit.monday.service.GitHubGraphQLService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class HeatmapService {

    @Inject
    GitHubGraphQLService graphQLService;

    @Inject
    HeatmapAnalyzer heatmapAnalyzer;

    @Inject
    MondayReportConfig config;

    /**
     * Generate heatmap data by analyzing activity across Quarkus areas and Quarkiverse extensions
     */
    public String generateHeatmapData(YearMonth currentMonth, YearMonth previousMonth) throws Exception {
        Log.info("📊 Collecting activity data...");

        // Fetch Quarkus area data for both periods
        Map<String, ActivityMetrics> currentQuarkusAreas = fetchQuarkusAreaActivity(currentMonth);
        Map<String, ActivityMetrics> previousQuarkusAreas = fetchQuarkusAreaActivity(previousMonth);

        Log.infof("   Found %d Quarkus areas in current period, %d in previous period",
                currentQuarkusAreas.size(), previousQuarkusAreas.size());

        // Fetch Quarkiverse repository data for both periods
        Map<String, ActivityMetrics> currentQuarkiverse = fetchQuarkiverseActivity(currentMonth);
        Map<String, ActivityMetrics> previousQuarkiverse = fetchQuarkiverseActivity(previousMonth);

        Log.infof("   Found %d Quarkiverse repos in current period, %d in previous period",
                currentQuarkiverse.size(), previousQuarkiverse.size());

        // Generate CSV
        return generateCSV(currentQuarkusAreas, previousQuarkusAreas, currentQuarkiverse, previousQuarkiverse);
    }

    /**
     * Fetch Quarkus area activity for a given month using GraphQL
     */
    private Map<String, ActivityMetrics> fetchQuarkusAreaActivity(YearMonth month) throws Exception {
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();

        return graphQLService.fetchQuarkusAreaActivity(startDate, endDate);
    }

    /**
     * Fetch Quarkiverse repository activity for a given month using GraphQL
     */
    private Map<String, ActivityMetrics> fetchQuarkiverseActivity(YearMonth month) throws Exception {
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();

        return graphQLService.fetchQuarkiverseActivity(startDate, endDate);
    }

    /**
     * Generate CSV from collected data
     */
    private String generateCSV(Map<String, ActivityMetrics> currentQuarkus, Map<String, ActivityMetrics> previousQuarkus,
                               Map<String, ActivityMetrics> currentQuarkiverse, Map<String, ActivityMetrics> previousQuarkiverse) {

        StringBuilder csv = new StringBuilder();
        csv.append("Category,Area/Extension,Curr Created,Curr Closed,Curr Open,Prev Created,Prev Closed,Prev Open,Net Change,Backlog Change,Backlog %\n");

        // Process Quarkus areas
        Set<String> allQuarkusAreas = new HashSet<>();
        allQuarkusAreas.addAll(currentQuarkus.keySet());
        allQuarkusAreas.addAll(previousQuarkus.keySet());

        for (String area : allQuarkusAreas.stream().sorted().toList()) {
            ActivityMetrics current = currentQuarkus.getOrDefault(area, new ActivityMetrics());
            ActivityMetrics previous = previousQuarkus.getOrDefault(area, new ActivityMetrics());

            int netChange = current.created() - current.closed();
            int prevNetChange = previous.created() - previous.closed();
            int backlogChange = current.openAtEnd() - previous.openAtEnd();
            double backlogPercent = calculateBacklogChangePercent(current.openAtEnd(), previous.openAtEnd());

            csv.append(String.format("Quarkus,%s,%d,%d,%d,%d,%d,%d,%+d,%+d,%.1f%%\n",
                    area,
                    current.created(), current.closed(), current.openAtEnd(),
                    previous.created(), previous.closed(), previous.openAtEnd(),
                    netChange, backlogChange, backlogPercent));
        }

        // Process Quarkiverse repositories
        Set<String> allQuarkiverseRepos = new HashSet<>();
        allQuarkiverseRepos.addAll(currentQuarkiverse.keySet());
        allQuarkiverseRepos.addAll(previousQuarkiverse.keySet());

        for (String repo : allQuarkiverseRepos.stream().sorted().toList()) {
            ActivityMetrics current = currentQuarkiverse.getOrDefault(repo, new ActivityMetrics());
            ActivityMetrics previous = previousQuarkiverse.getOrDefault(repo, new ActivityMetrics());

            int netChange = current.created() - current.closed();
            int prevNetChange = previous.created() - previous.closed();
            int backlogChange = current.openAtEnd() - previous.openAtEnd();
            double backlogPercent = calculateBacklogChangePercent(current.openAtEnd(), previous.openAtEnd());

            csv.append(String.format("Quarkiverse,%s,%d,%d,%d,%d,%d,%d,%+d,%+d,%.1f%%\n",
                    repo,
                    current.created(), current.closed(), current.openAtEnd(),
                    previous.created(), previous.closed(), previous.openAtEnd(),
                    netChange, backlogChange, backlogPercent));
        }

        return csv.toString();
    }

    /**
     * Calculate backlog change percentage
     */
    private double calculateBacklogChangePercent(int currentOpen, int previousOpen) {
        if (previousOpen == 0) {
            return currentOpen > 0 ? 100.0 : 0.0;
        }
        return ((double) (currentOpen - previousOpen) / previousOpen) * 100.0;
    }

    /**
     * Generate heatmap report using AI analysis
     */
    public String generateHeatmapReport(String csvData, YearMonth currentMonth, YearMonth previousMonth) {
        Log.info("🧠 Analyzing data with Gemini...");

        String report = heatmapAnalyzer.analyze(
            currentMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            previousMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            csvData
        );

        Log.info("✅ AI analysis complete");
        return report;
    }

    /**
     * Save report to file
     */
    public String saveReport(String report, YearMonth month, String outputPath) throws IOException {
        String fileName = String.format("%s-community-heatmap.md",
                month.format(DateTimeFormatter.ofPattern("yyyy-MM")));

        Path filePath;
        if (outputPath != null && !outputPath.isEmpty()) {
            filePath = Paths.get(outputPath, fileName);
        } else {
            Path outputDir = Paths.get(config.heatmapOutputDir());
            Files.createDirectories(outputDir);
            filePath = outputDir.resolve(fileName);
        }

        String fullReport = String.format("""
                        # Quarkus Community Activity Heatmap
                        
                        **Analysis Period**: %s
                        **Generated**: %s
                        
                        ---
                        
                        %s
                        """,
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                report);

        Files.writeString(filePath, fullReport);

        return filePath.toString();
    }
}
