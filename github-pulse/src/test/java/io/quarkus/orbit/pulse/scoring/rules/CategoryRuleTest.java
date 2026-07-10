package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategoryRuleTest {

    @Test
    void featureClassificationScoresHundred() {
        PrClassifier classifier = mock(PrClassifier.class);
        when(classifier.classify("Add feature X", "description")).thenReturn(PrCategory.FEATURE);

        Semaphore llmSemaphore = new Semaphore(15);
        var rule = new CategoryRule(classifier, llmSemaphore);

        PullRequestData pr = new PullRequestData("owner", "repo", 42, "Add feature X", "url",
                "author", "description", 10, 5, 2, List.of(), List.of());

        ScoringRule.ScoringResult result = rule.evaluate(pr, null);

        assertEquals("category", result.ruleName());
        assertEquals(100.0, result.normalizedPoints());
        assertEquals(PrCategory.FEATURE, result.metadata().get("category"));
    }

    @Test
    void bugFixClassificationScoresThirtyFive() {
        PrClassifier classifier = mock(PrClassifier.class);
        when(classifier.classify("Fix NPE", "desc")).thenReturn(PrCategory.BUG_FIX);

        var rule = new CategoryRule(classifier, new Semaphore(15));

        PullRequestData pr = new PullRequestData("owner", "repo", 43, "Fix NPE", "url",
                "author", "desc", 5, 2, 0, List.of(), List.of());

        ScoringRule.ScoringResult result = rule.evaluate(pr, null);

        assertEquals(35.0, result.normalizedPoints());
        assertEquals(PrCategory.BUG_FIX, result.metadata().get("category"));
    }
}
