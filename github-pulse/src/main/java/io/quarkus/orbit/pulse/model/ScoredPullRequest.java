package io.quarkus.orbit.pulse.model;

import java.util.List;
import java.util.Map;

public record ScoredPullRequest(
        PullRequestData pr,
        double score,
        List<String> reasons,
        Map<String, Object> metadata
) {}
