package io.quarkus.orbit.pulse.scoring;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ScoringEngine {

    private final Instance<ScoringRule> rules;

    public ScoringEngine(Instance<ScoringRule> rules) {
        this.rules = rules;
    }

    public ScoredPullRequest score(PullRequestData pr, PrPulseConfig.Rules repoRules) {
        double totalScore = 0;
        List<String> reasons = new ArrayList<>();

        for (ScoringRule rule : rules) {
            ScoringRule.ScoringResult result = rule.evaluate(pr, repoRules);
            totalScore += result.points();
            if (result.reason() != null) {
                reasons.add(result.reason());
            }
        }

        return new ScoredPullRequest(pr, totalScore, reasons);
    }
}
