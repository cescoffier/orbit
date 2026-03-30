package io.quarkus.orbit.pulse.scoring;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;

public interface ScoringRule {

    ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules);

    record ScoringResult(double points, String reason) {}
}
