package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SizeRuleTest {

    private final SizeRule rule = new SizeRule();

    private PrPulseConfig.Rules mockRules() {
        PrPulseConfig.Rules rules = mock(PrPulseConfig.Rules.class);
        when(rules.sizeScaleFactor()).thenReturn(8.1);
        when(rules.maxSizeScore()).thenReturn(100);
        return rules;
    }

    private PullRequestData prWithLines(int additions, int deletions) {
        return new PullRequestData("owner", "repo", 1, "title", "url", "author", "desc",
                additions, deletions, 0, List.of(), List.of());
    }

    @Test
    void logarithmicCurveSmallDiff() {
        var result = rule.evaluate(prWithLines(5, 5), mockRules());
        assertEquals("size", result.ruleName());
        // log2(1 + 10) * 8.1 ≈ 28.0
        assertTrue(result.normalizedPoints() > 25 && result.normalizedPoints() < 30,
                "10 lines should score ~28, got " + result.normalizedPoints());
    }

    @Test
    void logarithmicCurveLargeDiff() {
        var result = rule.evaluate(prWithLines(5000, 5000), mockRules());
        // log2(1 + 10000) * 8.1 ≈ 107 -> capped at 100
        assertEquals(100.0, result.normalizedPoints());
    }

    @Test
    void zeroLinesDiff() {
        var result = rule.evaluate(prWithLines(0, 0), mockRules());
        assertEquals(0.0, result.normalizedPoints());
    }

    @Test
    void ruleNameIsSize() {
        var result = rule.evaluate(prWithLines(50, 50), mockRules());
        assertEquals("size", result.ruleName());
    }
}
