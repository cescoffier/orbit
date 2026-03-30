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
            return new ScoringResult(0, null);
        }

        boolean touchesCritical = pr.filePaths().stream()
                .anyMatch(file -> criticalPaths.stream().anyMatch(file::startsWith));

        if (touchesCritical) {
            List<String> matched = pr.filePaths().stream()
                    .filter(file -> criticalPaths.stream().anyMatch(file::startsWith))
                    .toList();
            return new ScoringResult(
                    rules.criticalFilesBonus(),
                    "Touches critical paths: %s".formatted(matched)
            );
        }
        return new ScoringResult(0, null);
    }
}
