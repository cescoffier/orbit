package io.quarkus.orbit.pulse.scoring;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ScoringEngine {

    private final Instance<ScoringRule> rules;

    public ScoringEngine(Instance<ScoringRule> rules) {
        this.rules = rules;
    }

    public ScoredPullRequest score(PullRequestData pr, PrPulseConfig.Rules repoRules) {
        List<ScoringRule.ScoringResult> ruleResults = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>();
        double totalScore = 0;

        for (ScoringRule rule : rules) {
            ScoringRule.ScoringResult result = rule.evaluate(pr, repoRules);
            ruleResults.add(result);
            metadata.putAll(result.metadata());

            double weight = weightForRule(result.ruleName(), repoRules);
            totalScore += result.normalizedPoints() * weight;
        }

        return new ScoredPullRequest(pr, totalScore, ruleResults, metadata);
    }

    private double weightForRule(String ruleName, PrPulseConfig.Rules rules) {
        return switch (ruleName) {
            case "size" -> rules.sizeWeight();
            case "category" -> rules.categoryWeight();
            case "critical-path" -> rules.criticalPathWeight();
            case "comments" -> rules.commentWeight();
            default -> 0.0;
        };
    }
}
