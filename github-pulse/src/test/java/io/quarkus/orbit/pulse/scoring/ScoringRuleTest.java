package io.quarkus.orbit.pulse.scoring;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoringRuleTest {

    @Test
    void scoringResultWithMetadata() {
        var result = new ScoringRule.ScoringResult(10.0, "reason", Map.of("key", "value"));
        assertEquals(10.0, result.points());
        assertEquals("reason", result.reason());
        assertEquals("value", result.metadata().get("key"));
    }

    @Test
    void scoringResultWithoutMetadataDefaultsToEmptyMap() {
        var result = new ScoringRule.ScoringResult(5.0, "reason");
        assertEquals(5.0, result.points());
        assertNotNull(result.metadata());
        assertTrue(result.metadata().isEmpty());
    }
}
