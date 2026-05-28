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
    void returnsClassificationInMetadata() {
        PrClassifier classifier = mock(PrClassifier.class);
        when(classifier.classify("Add feature X", "description")).thenReturn(PrCategory.FEATURE);

        Semaphore llmSemaphore = new Semaphore(15);
        var rule = new CategoryRule(classifier, llmSemaphore);

        PullRequestData pr = new PullRequestData("owner", "repo", 42, "Add feature X", "url", "author", "description", 10, 5, 2, List.of(), List.of());

        PrPulseConfig.Rules rules = mock(PrPulseConfig.Rules.class);
        when(rules.featureScore()).thenReturn(45);

        ScoringRule.ScoringResult result = rule.evaluate(pr, rules);

        assertEquals(45.0, result.points());
        assertEquals(PrCategory.FEATURE, result.metadata().get("category"));
    }
}
