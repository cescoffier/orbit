package io.quarkus.orbit.monday.service.issues;

/**
 * Represents a stale pull request
 */
public record StalePR(
        String repository,
        int number,
        String url,
        String title,
        String author,
        String reason,
        long daysStale
) {
}
