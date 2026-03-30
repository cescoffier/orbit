package io.quarkus.orbit.monday.service.issues;

import java.util.List;

/**
 * Represents an issue with high activity (>10 comments)
 */
public record HotIssue(
    String repository,
    int number,
    String url,
    String title,
    String description,
    List<IssueComment> comments
) {}
