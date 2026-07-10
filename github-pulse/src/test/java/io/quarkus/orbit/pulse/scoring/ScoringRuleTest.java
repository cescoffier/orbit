package io.quarkus.orbit.pulse.scoring;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoringRuleTest {

    @Test
    void scoringResultWithAllFields() {
        var result = new ScoringRule.ScoringResult("size", 42.0, 85.0, "reason", Map.of("key", "value"));
        assertEquals("size", result.ruleName());
        assertEquals(42.0, result.points());
        assertEquals(85.0, result.normalizedPoints());
        assertEquals("reason", result.reason());
        assertEquals("value", result.metadata().get("key"));
    }

    @Test
    void scoringResultConvenienceConstructor() {
        var result = new ScoringRule.ScoringResult("category", 30.0, 100.0, "FEATURE");
        assertEquals("category", result.ruleName());
        assertEquals(30.0, result.points());
        assertEquals(100.0, result.normalizedPoints());
        assertEquals("FEATURE", result.reason());
        assertTrue(result.metadata().isEmpty());
    }
}
