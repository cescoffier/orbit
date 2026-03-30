package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SizeRule implements ScoringRule {

    @Override
    public ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules) {
        int totalLines = pr.additions() + pr.deletions();
        double rawPoints = totalLines * rules.linesChangedWeight();
        boolean capped = rawPoints > rules.maxSizeScore();
        double points = Math.min(rawPoints, rules.maxSizeScore());
        String reason = points > 0
                ? "Diff size: %d lines changed (weight=%.2f)%s".formatted(
                        totalLines, rules.linesChangedWeight(),
                        capped ? " [capped from %.0f to %d]".formatted(rawPoints, rules.maxSizeScore()) : "")
                : null;
        return new ScoringResult(points, reason);
    }
}
