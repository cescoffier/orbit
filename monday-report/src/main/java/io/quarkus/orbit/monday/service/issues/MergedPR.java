package io.quarkus.orbit.monday.service.issues;

/**
 * Represents a merged pull request
 */
public record MergedPR(
    String repository,
    int number,
    String url,
    String title,
    String author,
    int filesChanged,
    int additions,
    int deletions,
    String description
) {}
