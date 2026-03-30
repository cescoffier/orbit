package io.quarkus.orbit.monday.service;

import io.quarkus.orbit.monday.service.discussions.AnalyzedDiscussion;
import io.quarkus.orbit.monday.service.issues.HotIssue;
import io.quarkus.orbit.monday.service.issues.MergedPR;
import io.quarkus.orbit.monday.service.issues.StalePR;

import java.util.List;

/**
 * Holds all activity data for a repository
 */
public record RepoActivity(
    String repoName,
    List<MergedPR> mergedPRs,
    List<HotIssue> hotIssues,
    List<StalePR> stalePRsChangesRequested,
    List<StalePR> stalePRsAwaitingReview,
    List<AnalyzedDiscussion> discussions
) {
    public boolean hasActivity() {
        return !mergedPRs.isEmpty() || !hotIssues.isEmpty() ||
               !stalePRsChangesRequested.isEmpty() || !stalePRsAwaitingReview.isEmpty() ||
               !discussions.isEmpty();
    }
}
