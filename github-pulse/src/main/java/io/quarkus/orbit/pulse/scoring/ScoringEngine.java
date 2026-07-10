package io.quarkus.orbit.pulse.scoring;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
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

    @ActivateRequestContext
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

        PrCategory category = metadata.get("category") instanceof PrCategory pc ? pc : null;
        String summary = metadata.get("summary") instanceof String s ? s : null;
        return new ScoredPullRequest(pr, totalScore, ruleResults, metadata, category, summary);
    }

    public double weightForRule(String ruleName, PrPulseConfig.Rules rules) {
        return switch (ruleName) {
            case "size" -> rules.sizeWeight();
            case "category" -> rules.categoryWeight();
            case "critical-path" -> rules.criticalPathWeight();
            case "comments" -> rules.commentWeight();
            default -> 0.0;
        };
    }
}
