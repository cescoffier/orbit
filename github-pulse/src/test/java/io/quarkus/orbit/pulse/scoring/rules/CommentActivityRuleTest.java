package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommentActivityRuleTest {

    private final CommentActivityRule rule = new CommentActivityRule();

    private PullRequestData prWithComments(int count) {
        return new PullRequestData("owner", "repo", 1, "title", "url", "author", "desc",
                10, 5, count, List.of(), List.of());
    }

    @Test
    void zeroCommentsScoresZero() {
        var result = rule.evaluate(prWithComments(0), null);
        assertEquals("comments", result.ruleName());
        assertEquals(0.0, result.normalizedPoints());
    }

    @Test
    void twoCommentsScoresForty() {
        var result = rule.evaluate(prWithComments(2), null);
        assertEquals(40.0, result.normalizedPoints());
    }

    @Test
    void fiveOrMoreCommentsCapsAtHundred() {
        var result = rule.evaluate(prWithComments(5), null);
        assertEquals(100.0, result.normalizedPoints());

        var result10 = rule.evaluate(prWithComments(10), null);
        assertEquals(100.0, result10.normalizedPoints());
    }
}
