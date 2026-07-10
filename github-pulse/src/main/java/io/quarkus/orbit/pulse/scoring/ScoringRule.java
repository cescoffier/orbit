package io.quarkus.orbit.pulse.scoring;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;

import java.util.Map;

public interface ScoringRule {

    ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules);

    record ScoringResult(String ruleName, double points, double normalizedPoints, String reason, Map<String, Object> metadata) {
        public ScoringResult(String ruleName, double points, double normalizedPoints, String reason) {
            this(ruleName, points, normalizedPoints, reason, Map.of());
        }
    }
}
