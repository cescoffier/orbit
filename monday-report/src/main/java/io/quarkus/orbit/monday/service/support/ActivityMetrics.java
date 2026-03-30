package io.quarkus.orbit.monday.service.support;

/**
 * Holds activity metrics for a specific area/repository
 */
public record ActivityMetrics(
    int created,    // Issues/PRs created during the period
    int closed,     // Issues/PRs closed during the period
    int openAtEnd   // Issues/PRs still open at the end of the period
) {
    public ActivityMetrics() {
        this(0, 0, 0);
    }

    public ActivityMetrics add(ActivityMetrics other) {
        return new ActivityMetrics(
            this.created + other.created,
            this.closed + other.closed,
            this.openAtEnd + other.openAtEnd
        );
    }
}
