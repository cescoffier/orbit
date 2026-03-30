package io.quarkus.orbit.monday.service.issues;

/**
 * Represents a comment on an issue
 */
public record IssueComment(
    String author,
    String body
) {}
