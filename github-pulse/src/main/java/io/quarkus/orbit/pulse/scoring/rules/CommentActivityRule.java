package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CommentActivityRule implements ScoringRule {

    @Override
    public ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules) {
        int count = pr.commentCount();
        double normalized = Math.min(count * 20.0, 100.0);

        String reason = count > 0
                ? "Comment activity: %d comments (normalized: %.0f)".formatted(count, normalized)
                : null;
        return new ScoringResult("comments", count, normalized, reason);
    }
}
