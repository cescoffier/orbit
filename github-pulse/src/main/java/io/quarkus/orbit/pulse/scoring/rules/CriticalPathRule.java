package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class CriticalPathRule implements ScoringRule {

    @Override
    public ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules) {
        List<String> criticalPaths = rules.criticalPaths().orElse(List.of());
        if (criticalPaths.isEmpty()) {
            return new ScoringResult("critical-path", 0, 0, null);
        }

        List<String> matched = pr.filePaths().stream()
                .filter(file -> criticalPaths.stream().anyMatch(file::startsWith))
                .toList();

        if (!matched.isEmpty()) {
            return new ScoringResult("critical-path", matched.size(), 100.0,
                    "Touches critical paths: %s".formatted(matched));
        }
        return new ScoringResult("critical-path", 0, 0, null);
    }
}
