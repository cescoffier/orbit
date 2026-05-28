package io.quarkus.orbit.pulse.scoring;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoringEngineTest {

    @Test
    void aggregatesMetadataFromAllRules() {
        ScoringRule ruleA = (pr, rules) -> new ScoringRule.ScoringResult(10, "rule A", Map.of("keyA", "valueA"));
        ScoringRule ruleB = (pr, rules) -> new ScoringRule.ScoringResult(20, "rule B", Map.of("keyB", "valueB"));

        Instance<ScoringRule> instance = new FakeInstance<>(List.of(ruleA, ruleB));
        var engine = new ScoringEngine(instance);

        PullRequestData pr = new PullRequestData("owner", "repo", 1, "title", "url", "author", "desc", 10, 5, 2, List.of(), List.of());
        ScoredPullRequest scored = engine.score(pr, null);

        assertEquals(30.0, scored.score());
        assertEquals(2, scored.reasons().size());
        assertEquals("valueA", scored.metadata().get("keyA"));
        assertEquals("valueB", scored.metadata().get("keyB"));
    }

    private static class FakeInstance<T> implements Instance<T> {
        private final List<T> items;
        FakeInstance(List<T> items) { this.items = items; }
        @Override public Iterator<T> iterator() { return items.iterator(); }

        // Unused Instance methods — minimal stubs
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
