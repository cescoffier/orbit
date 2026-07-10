package io.quarkus.orbit.pulse.scoring;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScoringEngineTest {

    @Test
    void computesWeightedAverage() {
        // size: normalized=50, weight=0.20 -> 10
        // category: normalized=100, weight=0.35 -> 35
        ScoringRule ruleA = (pr, rules) -> new ScoringRule.ScoringResult("size", 200, 50.0, "200 lines");
        ScoringRule ruleB = (pr, rules) -> new ScoringRule.ScoringResult("category", 100, 100.0, "FEATURE",
                Map.of("category", PrCategory.FEATURE));

        Instance<ScoringRule> instance = new FakeInstance<>(List.of(ruleA, ruleB));
        var engine = new ScoringEngine(instance);

        PrPulseConfig.Rules rules = mock(PrPulseConfig.Rules.class);
        when(rules.sizeWeight()).thenReturn(0.20);
        when(rules.categoryWeight()).thenReturn(0.35);
        when(rules.criticalPathWeight()).thenReturn(0.25);
        when(rules.commentWeight()).thenReturn(0.20);

        PullRequestData pr = new PullRequestData("owner", "repo", 1, "title", "url", "author",
                "desc", 10, 5, 2, List.of(), List.of());
        ScoredPullRequest scored = engine.score(pr, rules);

        // 50*0.20 + 100*0.35 = 10 + 35 = 45
        assertEquals(45.0, scored.score(), 0.01);
        assertEquals(2, scored.ruleResults().size());
        assertEquals(PrCategory.FEATURE, scored.metadata().get("category"));
        assertEquals(PrCategory.FEATURE, scored.category(), "category should be FEATURE from rule metadata");
        assertNull(scored.summary(), "summary should be null since test rule doesn't set it");
    }

    @Test
    void unknownRuleNameGetsZeroWeight() {
        ScoringRule ruleA = (pr, rules) -> new ScoringRule.ScoringResult("unknown-rule", 50, 80.0, "test");

        Instance<ScoringRule> instance = new FakeInstance<>(List.of(ruleA));
        var engine = new ScoringEngine(instance);

        PrPulseConfig.Rules rules = mock(PrPulseConfig.Rules.class);
        when(rules.sizeWeight()).thenReturn(0.20);
        when(rules.categoryWeight()).thenReturn(0.35);
        when(rules.criticalPathWeight()).thenReturn(0.25);
        when(rules.commentWeight()).thenReturn(0.20);

        PullRequestData pr = new PullRequestData("owner", "repo", 1, "title", "url", "author",
                "desc", 10, 5, 2, List.of(), List.of());
        ScoredPullRequest scored = engine.score(pr, rules);

        assertEquals(0.0, scored.score(), 0.01);
        assertNull(scored.category());
        assertNull(scored.summary());
    }

    private static class FakeInstance<T> implements Instance<T> {
        private final List<T> items;
        FakeInstance(List<T> items) { this.items = items; }
        @Override public Iterator<T> iterator() { return items.iterator(); }
        @Override public Instance<T> select(java.lang.annotation.Annotation... qualifiers) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public boolean isUnsatisfied() { return false; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public boolean isResolvable() { return true; }
        @Override public T get() { return items.getFirst(); }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends Handle<T>> handles() { throw new UnsupportedOperationException(); }
    }
}
