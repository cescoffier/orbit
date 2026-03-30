package io.quarkus.orbit.wg.detection.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Represents an Issue or Pull Request candidate for Working Group association.
 */
public record IssueOrPR(
        String id,  // GitHub node ID (required for GraphQL mutations)
        String owner,
        String repo,
        int number,
        String title,
        String body,
        String url,
        String state,
        ItemType type,
        Instant createdAt,
        Instant updatedAt,
        List<String> labels) {

    private static final Set<String> EXCLUDED_LABELS = Set.of(
            "invalid",
            "triage/invalid",
            "triage/on-ice",
            "wontfix",
            "triage/wontfix",
            "duplicate",
            "triage/duplicate"
    );

    /**
     * Returns true if this issue/PR has a label that should exclude it from detection.
     */
    public boolean hasExcludedLabel() {
        if (labels == null) {
            return false;
        }
        return labels.stream().anyMatch(EXCLUDED_LABELS::contains);
    }

    public enum ItemType {
        ISSUE,
        PULL_REQUEST
    }

    /**
     * Returns true if this is an open issue
     */
    public boolean isOpenIssue() {
        return type == ItemType.ISSUE && "OPEN".equalsIgnoreCase(state);
    }

    /**
     * Returns true if this is an open PR
     */
    public boolean isOpenPR() {
        return type == ItemType.PULL_REQUEST && "OPEN".equalsIgnoreCase(state);
    }

    /**
     * Returns true if this is a closed issue
     */
    public boolean isClosedIssue() {
        return type == ItemType.ISSUE && "CLOSED".equalsIgnoreCase(state);
    }

    /**
     * Returns true if this is a merged PR
     */
    public boolean isMergedPR() {
        return type == ItemType.PULL_REQUEST && "MERGED".equalsIgnoreCase(state);
    }

    /**
     * Returns a human-readable identifier (owner/repo#number)
     */
    public String getDisplayId() {
        return owner() + "/" + repo() + "#" + number();
    }

    /**
     * Determines the appropriate status field value for this item when added to a project
     */
    public String determineProjectStatus() {
        if (isOpenIssue()) {
            return "Todo";
        } else if (isOpenPR()) {
            return "In Progress";
        } else if (isClosedIssue() || isMergedPR()) {
            return "Done";
        }
        return "Todo";
    }
}
