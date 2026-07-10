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
        if (totalLines == 0) {
            return new ScoringResult("size", 0, 0, null);
        }

        double raw = Math.log(1 + totalLines) / Math.log(2);
        double normalized = Math.min(raw * rules.sizeScaleFactor(), rules.maxSizeScore());

        String reason = "Diff size: %d lines (log2 normalized: %.1f)".formatted(totalLines, normalized);
        return new ScoringResult("size", totalLines, normalized, reason);
    }
}
