package io.quarkus.orbit.pulse.model;

/**
 * A pull request associated with a specific release tag.
 *
 * @param number   PR number
 * @param title    PR title
 * @param url      HTML URL of the PR on GitHub
 * @param author   GitHub login of the PR author
 * @param mergedAt ISO-8601 merged-at timestamp (may be null when sourced from release notes)
 */
public record ReleasePullRequest(
        int number,
        String title,
        String url,
        String author,
        String mergedAt
) {}
