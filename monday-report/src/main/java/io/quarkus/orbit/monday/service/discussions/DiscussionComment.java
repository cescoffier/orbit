package io.quarkus.orbit.monday.service.discussions;

/**
 * Represents a comment in a GitHub discussion
 */
public record DiscussionComment(
    String author,
    String body,
    String createdAt
) {}
