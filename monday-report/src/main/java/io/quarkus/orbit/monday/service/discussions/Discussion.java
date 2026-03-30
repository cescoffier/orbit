package io.quarkus.orbit.monday.service.discussions;

import java.util.List;

/**
 * Represents a GitHub discussion with full content
 */
public record Discussion(
    int number,
    String title,
    String url,
    String body,
    String createdAt,
    String updatedAt,
    List<DiscussionComment> comments
) {}
