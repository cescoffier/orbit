package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CommentActivityRule implements ScoringRule {

    @Override
    public ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules) {
        double points = pr.commentCount() * rules.commentActivityWeight();
        String reason = points > 0
                ? "Comment activity: %d comments (weight=%d)".formatted(pr.commentCount(), rules.commentActivityWeight())
                : null;
        return new ScoringResult(points, reason);
    }
}
