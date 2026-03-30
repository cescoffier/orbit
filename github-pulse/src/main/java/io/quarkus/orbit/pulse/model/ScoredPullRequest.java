package io.quarkus.orbit.pulse.model;

import java.util.List;

public record ScoredPullRequest(
        PullRequestData pr,
        double score,
        List<String> reasons
) {}
