package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CriticalPathRuleTest {

    private final CriticalPathRule rule = new CriticalPathRule();

    @Test
    void matchesCriticalPathScoresHundred() {
        PrPulseConfig.Rules rules = mock(PrPulseConfig.Rules.class);
        when(rules.criticalPaths()).thenReturn(Optional.of(List.of("core/src/main/java")));

        var pr = new PullRequestData("owner", "repo", 1, "title", "url", "author", "desc",
                10, 5, 0, List.of("core/src/main/java/Foo.java", "README.md"), List.of());

        var result = rule.evaluate(pr, rules);
        assertEquals("critical-path", result.ruleName());
        assertEquals(100.0, result.normalizedPoints());
    }

    @Test
    void noCriticalPathMatchScoresZero() {
        PrPulseConfig.Rules rules = mock(PrPulseConfig.Rules.class);
        when(rules.criticalPaths()).thenReturn(Optional.of(List.of("core/src/main/java")));

        var pr = new PullRequestData("owner", "repo", 1, "title", "url", "author", "desc",
                10, 5, 0, List.of("docs/README.md"), List.of());

        var result = rule.evaluate(pr, rules);
        assertEquals(0.0, result.normalizedPoints());
    }

    @Test
    void noCriticalPathsConfiguredScoresZero() {
        PrPulseConfig.Rules rules = mock(PrPulseConfig.Rules.class);
        when(rules.criticalPaths()).thenReturn(Optional.empty());

        var pr = new PullRequestData("owner", "repo", 1, "title", "url", "author", "desc",
                10, 5, 0, List.of("core/src/main/java/Foo.java"), List.of());

        var result = rule.evaluate(pr, rules);
        assertEquals(0.0, result.normalizedPoints());
    }
}
