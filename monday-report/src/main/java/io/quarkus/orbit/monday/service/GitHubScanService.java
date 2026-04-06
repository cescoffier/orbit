package io.quarkus.orbit.monday.service;

import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.service.discussions.AnalyzedDiscussion;
import io.quarkus.orbit.monday.service.discussions.Discussion;
import io.quarkus.orbit.monday.service.discussions.DiscussionAnalysisService;
import io.quarkus.orbit.monday.service.discussions.GitHubDiscussionService;
import io.quarkus.orbit.monday.service.issues.HotIssue;
import io.quarkus.orbit.monday.service.issues.IssueComment;
import io.quarkus.orbit.monday.service.issues.MergedPR;
import io.quarkus.orbit.monday.service.issues.StalePR;
import io.quarkus.orbit.monday.service.support.ConcurrencyService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class GitHubScanService {

    @Inject
    GitHubDiscussionService discussionService;

    @Inject
    DiscussionAnalysisService discussionAnalysisService;

    @Inject
    ConcurrencyService executor;

    @Inject
    GithubService github;

    public RepoActivity scanRepository(String repoName, LocalDate weekStart, LocalDate weekEnd) throws Exception {
        Log.infof("  [%s] Scanning repository", repoName);

        GHRepository repo = github.get().getRepository(repoName);

        String weekStartStr = weekStart.toString();
        String weekEndStr = weekEnd.toString();
        LocalDate twoWeeksAgo = LocalDate.now().minusWeeks(2);

        // 1: Hot Issues (>10 comments, updated last week)
        Uni<List<HotIssue>> hotIssuesTask = executor.submit("Fetch hot issues from " + repoName, () -> fetchHotIssues(repoName, weekStartStr, weekEndStr));

        // 2: Merged PRs (with full details for AI analysis)
        Uni<List<MergedPR>> mergedPRsTask = executor.submit("Fetch merged PR From " + repoName, () -> fetchMergedPRs(repo, repoName, weekStartStr, weekEndStr));

        // 3: Stale PRs with changes requested (no activity for 2 weeks)
        Uni<List<StalePR>> stalePRsChangesRequestedTask = executor.submit("Detect state PRs requiring changes from " + repoName, () ->
                fetchStalePRs(repoName, twoWeeksAgo, "Changes Requested", "review:changes_requested"));

        // 4: Non-draft PRs awaiting review (no review for 2 weeks)
        Uni<List<StalePR>> awaitingReviewTask = executor.submit("Detect stale PRs awaiting reviews from " + repoName, () ->
                fetchStalePRs(repoName, twoWeeksAgo, "Awaiting Review", "draft:false review:none"));

        // 5: Discussions (new or hot) - Using GraphQL
        Uni<List<AnalyzedDiscussion>> discussionsTask = executor.submit("Fetch and analyze discussions from " + repoName, () -> analyzeDiscussions(repoName));

        var results = Uni.combine().all().unis(hotIssuesTask, mergedPRsTask, stalePRsChangesRequestedTask, awaitingReviewTask, discussionsTask).collectFailures().asTuple().await().indefinitely();

        // Extract
        List<HotIssue> hotIssues = results.getItem1();
        List<MergedPR> mergedPRs = results.getItem2();
        List<StalePR> stalePRsChanges = results.getItem3();
        List<StalePR> awaitingReview = results.getItem4();
        List<AnalyzedDiscussion> discussions = results.getItem5();

        return new RepoActivity(repoName, mergedPRs, hotIssues, stalePRsChanges, awaitingReview, discussions);
    }

    @ActivateRequestContext
    List<AnalyzedDiscussion> analyzeDiscussions(String repoName) {
        List<Discussion> discussions = discussionService.fetchDiscussions(repoName);
        List<AnalyzedDiscussion> analyzed = new ArrayList<>();

        for (Discussion discussion : discussions) {
            try {
                AnalyzedDiscussion result = discussionAnalysisService.analyzeDiscussion(discussion);
                analyzed.add(result);
            } catch (Exception e) {
                Log.warnf(e, "Failed to analyze discussion #%d", discussion.number());
            }
        }

        return analyzed;
    }

    boolean isOnIce(GHIssue issue) {
        try {
            for (GHLabel label : issue.getLabels()) {
                if (label.getName().contains("triage/on-ice")) {
                    return true;
                }
            }

        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Fetch hot issues updated within the specified week that have more than 10 comments.
     *
     * @param repoName     the repository name
     * @param weekStartStr the start date of the week in YYYY-MM-DD format
     * @param weekEndStr   the end date of the week in YYYY-MM-DD format
     * @return a list of HotIssue objects
     * @throws Exception if an error occurs during fetching
     */
    private List<HotIssue> fetchHotIssues(String repoName, String weekStartStr, String weekEndStr) throws Exception {
        var search = github.get().searchIssues()
                .q("repo:" + repoName + " updated:" + weekStartStr + ".." + weekEndStr + " comments:>10 type:issue")
                .list().toList();

        List<Uni<HotIssue>> tasks = new ArrayList<>();
        for (GHIssue ghIssue : search) {
            if (!isOnIce(ghIssue)) {
                tasks.add(executor.submit("Retrieve details for " + repoName + "#" + ghIssue.getNumber(), () -> buildHotIssue(ghIssue, repoName)));
            }
        }
        return Uni.join().all(tasks).andCollectFailures().await().indefinitely().stream()
                .filter(issue -> issue != null)
                .toList();
    }

    /**
     * Fetch merged PRs within the specified week with detailed information.
     *
     * @param repo         the GitHub repository object
     * @param repoName     the repository name
     * @param weekStartStr the start date of the week in YYYY-MM-DD format
     * @param weekEndStr   the end date of the week in YYYY-MM-DD format
     * @return a list of MergedPR objects
     * @throws Exception if an error occurs during fetching
     */
    private List<MergedPR> fetchMergedPRs(GHRepository repo, String repoName, String weekStartStr, String weekEndStr) throws Exception {
        var search = github.get().searchIssues()
                .q("repo:" + repoName + " is:pr is:merged merged:" + weekStartStr + ".." + weekEndStr)
                .list().toList();

        List<Uni<MergedPR>> tasks = new ArrayList<>();
        for (GHIssue ghIssue : search) {
            tasks.add(executor.submit("Retrieve details for " + repoName + "#" + ghIssue.getNumber(), () -> buildMergedPR(repo, ghIssue, repoName)));
        }
        return Uni.join().all(tasks).andCollectFailures().await().indefinitely().stream()
                .filter(pr -> pr != null)
                .toList();
    }

    /**
     * Fetch stale PRs based on the provided criteria.
     *
     * @param repoName        the repository name
     * @param twoWeeksAgo     the date two weeks ago
     * @param reason          the reason for staleness
     * @param additionalQuery additional query parameters
     * @return a list of StalePR objects
     * @throws Exception if an error occurs during fetching
     */
    private List<StalePR> fetchStalePRs(String repoName, LocalDate twoWeeksAgo, String reason, String additionalQuery) throws Exception {
        List<GHPullRequest> search;
        search = github.get().searchPullRequests()
                .q("repo:" + repoName + " is:open " + additionalQuery + " updated:<" + twoWeeksAgo)
                .list().toList();

        List<Uni<StalePR>> tasks = new ArrayList<>();
        for (GHPullRequest ghIssue : search) {
            if (!isOnIce(ghIssue) && !ghIssue.isDraft()) {
                tasks.add(executor.submit("Retrieve details for staled PR: " + repoName + "#" + ghIssue.getNumber(), () -> buildStalePR(ghIssue, reason, repoName)));
            }
        }

        return Uni.join().all(tasks).andCollectFailures().await().indefinitely().stream()
                .filter(pr -> pr != null)
                .toList();
    }


    private HotIssue buildHotIssue(GHIssue issue, String repoName) {
        try {
            String url = "https://github.com/" + repoName + "/issues/" + issue.getNumber();
            String description = issue.getBody() != null ? issue.getBody() : "";
            if (description.length() > 500) {
                description = description.substring(0, 500) + "...";
            }

            List<IssueComment> comments = new ArrayList<>();
            int count = issue.getCommentsCount();
            if (count > 0) {
                List<GHIssueComment> ghComments = issue.getComments();
                for (GHIssueComment comment : ghComments) {
                    comments.add(new IssueComment(
                            comment.getUser().getLogin(),
                            comment.getBody()
                    ));
                }
            }

            return new HotIssue(repoName,
                    issue.getNumber(),
                    url,
                    issue.getTitle(),
                    description,
                    comments
            );
        } catch (Exception e) {
            return null;
        }
    }

    private MergedPR buildMergedPR(GHRepository repo, GHIssue pr, String repoName) {
        try {
            GHPullRequest fullPR = repo.getPullRequest(pr.getNumber());

            String description = pr.getBody() != null ? pr.getBody() : "";
            if (description.length() > 500) {
                description = description.substring(0, 500) + "...";
            }

            String url = "https://github.com/" + repoName + "/pull/" + pr.getNumber();

            return new MergedPR(
                    repoName,
                    pr.getNumber(),
                    url,
                    pr.getTitle(),
                    pr.getUser().getLogin(),
                    fullPR.getChangedFiles(),
                    fullPR.getAdditions(),
                    fullPR.getDeletions(),
                    description
            );
        } catch (Exception e) {
            return null;
        }
    }

    private StalePR buildStalePR(GHIssue pr, String reason, String repoName) {
        try {
            // Check if there are some recent comments (within the last 2 weeks)
            List<GHIssueComment> comments = pr.getComments();
            for (GHIssueComment comment : comments) {
                LocalDate commentDate = comment.getCreatedAt().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                long daysSinceComment = java.time.temporal.ChronoUnit.DAYS.between(commentDate, LocalDate.now());
                if (daysSinceComment <= 14) {
                    // Recent comment found, skip this PR
                    return null;
                }
            }

            LocalDate updated = pr.getUpdatedAt().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            long daysStale = java.time.temporal.ChronoUnit.DAYS.between(updated, LocalDate.now());
            String url = "https://github.com/" + repoName + "/pull/" + pr.getNumber();

            return new StalePR(
                    repoName,
                    pr.getNumber(),
                    url,
                    pr.getTitle(),
                    pr.getUser().getLogin(),
                    reason,
                    daysStale
            );
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> fetchContributors(GHRepository repo, String repoName, String weekStartStr, String weekEndStr) throws Exception {
        // Fetch all merged PRs for the week
        var mergedPRs = github.get().searchIssues()
                .q("repo:" + repoName + " is:pr is:merged merged:" + weekStartStr + ".." + weekEndStr)
                .list().toList();

        if (mergedPRs.isEmpty()) {
            return List.of();
        }

        List<String> contributorSummaries = new ArrayList<>();
        java.util.Map<String, ContributorInfo> contributorMap = new java.util.HashMap<>();

        // Analyze each merged PR
        for (GHIssue issue : mergedPRs) {
            try {
                GHPullRequest pr = repo.getPullRequest(issue.getNumber());
                String author = pr.getUser().getLogin();
                int additions = pr.getAdditions();
                int deletions = pr.getDeletions();
                int filesChanged = pr.getChangedFiles();
                int totalChanges = additions + deletions;

                // Aggregate contributor stats
                contributorMap.putIfAbsent(author, new ContributorInfo(author));
                ContributorInfo info = contributorMap.get(author);
                info.prCount++;
                info.totalAdditions += additions;
                info.totalDeletions += deletions;
                info.totalFilesChanged += filesChanged;

                // Track largest PR
                if (totalChanges > info.largestPRSize) {
                    info.largestPRSize = totalChanges;
                    info.largestPRNumber = pr.getNumber();
                    info.largestPRTitle = pr.getTitle();
                }

                // Check if new contributor (first PR in repo)
                if (info.prCount == 1) {
                    try {
                        var authorPRs = github.get().searchIssues()
                                .q("repo:" + repoName + " is:pr author:" + author + " is:merged").list();

                        // If this is their only merged PR, they're a new contributor
                        if (authorPRs.getTotalCount() == 1) {
                            info.isNewContributor = true;
                        }
                    } catch (Exception e) {
                        Log.debugf("Failed to check contributor history for %s: %s", author, e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.debugf("Failed to analyze PR for contributor stats: %s", e.getMessage());
            }
        }

        // Format output - prioritize new contributors and significant contributions
        for (ContributorInfo info : contributorMap.values()) {
            String authorUrl = "https://github.com/" + info.author;

            // New contributors
            if (info.isNewContributor) {
                contributorSummaries.add(String.format(
                        "- 🎉 [NEW] [@%s](%s) - First contribution! [PR #%d](%s) (%d files, +%d/-%d)\n",
                        info.author, authorUrl, info.largestPRNumber,
                        "https://github.com/" + repoName + "/pull/" + info.largestPRNumber,
                        info.totalFilesChanged, info.totalAdditions, info.totalDeletions
                ));
            }
            // Significant contributions (large PRs or multiple PRs)
            else if (info.largestPRSize > 500 || info.prCount > 3) {
                contributorSummaries.add(String.format(
                        "- 💪 [@%s](%s) - %d PR%s merged (%d files, +%d/-%d) - Largest: [#%d](%s) \"%s\"\n",
                        info.author, authorUrl, info.prCount, info.prCount > 1 ? "s" : "",
                        info.totalFilesChanged, info.totalAdditions, info.totalDeletions,
                        info.largestPRNumber,
                        "https://github.com/" + repoName + "/pull/" + info.largestPRNumber,
                        info.largestPRTitle.length() > 60 ? info.largestPRTitle.substring(0, 60) + "..." : info.largestPRTitle
                ));
            }
        }

        return contributorSummaries;
    }

    // Helper class to track contributor information
    private static class ContributorInfo {
        String author;
        int prCount = 0;
        int totalAdditions = 0;
        int totalDeletions = 0;
        int totalFilesChanged = 0;
        int largestPRSize = 0;
        int largestPRNumber = 0;
        String largestPRTitle = "";
        boolean isNewContributor = false;

        ContributorInfo(String author) {
            this.author = author;
        }
    }
}
