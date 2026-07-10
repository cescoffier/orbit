package io.quarkus.orbit.pulse.service;

import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisServiceTest {

    @Test
    void scoredPullRequestIncludesCategoryAndSummary() {
        var pr = new PullRequestData("owner", "repo", 42, "Fix NPE", "https://github.com/owner/repo/pull/42",
                "author", "Fixes a null pointer", 10, 5, 2, List.of("src/Main.java"), List.of("bug"));

        var result = new ScoredPullRequest(pr, 35.0,
                List.of(new ScoringRule.ScoringResult("category", 35, 35, "Category: BUG_FIX", Map.of("category", PrCategory.BUG_FIX))),
                Map.of("category", PrCategory.BUG_FIX),
                PrCategory.BUG_FIX,
                "Fixes a null pointer exception in the main handler");

        assertEquals(PrCategory.BUG_FIX, result.category());
        assertEquals("Fixes a null pointer exception in the main handler", result.summary());
        assertEquals(35.0, result.score());
    }
}
