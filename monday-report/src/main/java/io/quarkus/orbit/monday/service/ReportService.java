package io.quarkus.orbit.monday.service;

import io.quarkus.orbit.monday.config.MondayReportConfig;
import io.quarkus.orbit.monday.service.discussions.AnalyzedDiscussion;
import io.quarkus.orbit.monday.service.issues.HotIssue;
import io.quarkus.orbit.monday.service.issues.MergedPR;
import io.quarkus.orbit.monday.service.issues.StalePR;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@ApplicationScoped
public class ReportService {

    private static final Logger LOG = Logger.getLogger(ReportService.class);

    @Inject
    ExecutiveBriefingGenerator briefingGenerator;

    @Inject
    MondayReportConfig config;

    public String generateAIReport(List<RepoActivity> repositories, LocalDate weekStart, LocalDate weekEnd) {
        // Filter out repositories with no activity
        List<RepoActivity> activeRepos = repositories.stream()
                .filter(RepoActivity::hasActivity)
                .toList();

        if (activeRepos.isEmpty()) {
            LOG.info("No significant activity found.");
            return "# Monday Morning Executive Briefing\n\nNo significant activity found for the week " + weekStart + " to " + weekEnd + ".";
        }

        // Build parameters.
        List<HotIssue> allHotIssues = activeRepos.stream()
                .flatMap(repo -> repo.hotIssues().stream())
                .toList();

        List<StalePR> stalePRs = activeRepos.stream()
                .flatMap(repo -> repo.stalePRsChangesRequested().stream())
                .toList();

        List<StalePR> stalePRSAwaitingReview = activeRepos.stream()
                .flatMap(repo -> repo.stalePRsAwaitingReview().stream())
                .toList();

        List<MergedPR> allMergedPRs = activeRepos.stream()
                .flatMap(repo -> repo.mergedPRs().stream())
                .toList();

        List<AnalyzedDiscussion> allDiscussions = activeRepos.stream()
                .flatMap(repo -> repo.discussions().stream())
                .toList();

        LOG.infof("Sending data for %d repositories to Gemini...", activeRepos.size());

        String report = briefingGenerator.generateBriefing(
            weekStart.toString(),
            weekEnd.toString(),
            allHotIssues, stalePRs, stalePRSAwaitingReview, allMergedPRs, allDiscussions
        );

        LOG.info("\n" + report);
        return report;
    }

    public void saveReport(String report, LocalDate weekStart, String outputDirOverride) throws IOException {
        String datePrefix = weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String fileName = datePrefix + "-briefing.md";

        Path outputDir = Paths.get(outputDirOverride != null ? outputDirOverride : config.outputDir());
        Files.createDirectories(outputDir);

        Path filePath = outputDir.resolve(fileName);

        String fullContent = "# Monday Morning Executive Briefing\n\n" +
                "**Week:** " + weekStart + " to " + weekStart.plusDays(6) + "\n" +
                "**Generated:** " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n\n" +
                "---\n\n" +
                report;

        Files.writeString(filePath, fullContent);

        LOG.infof("Report saved to: %s", filePath);
    }
}
