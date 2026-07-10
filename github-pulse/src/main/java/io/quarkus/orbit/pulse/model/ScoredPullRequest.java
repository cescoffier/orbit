package io.quarkus.orbit.pulse.model;

import io.quarkus.orbit.pulse.scoring.ScoringRule;

import java.util.List;
import java.util.Map;

public record ScoredPullRequest(
        PullRequestData pr,
        double score,
        List<ScoringRule.ScoringResult> ruleResults,
        Map<String, Object> metadata
) {}
