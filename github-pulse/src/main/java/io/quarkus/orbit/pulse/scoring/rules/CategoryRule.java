package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class CategoryRule implements ScoringRule {

    private static final Logger LOG = Logger.getLogger(CategoryRule.class);

    private final PrClassifier classifier;
    private final Semaphore llmSemaphore;

    CategoryRule(PrClassifier classifier, @Named("llmSemaphore") Semaphore llmSemaphore) {
        this.classifier = classifier;
        this.llmSemaphore = llmSemaphore;
    }

    @Override
    public ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules) {
        try {
            PrClassification classification = classifyWithRateLimit(pr);

            double normalized = switch (classification.category()) {
                case FEATURE -> 100.0;
                case ENHANCEMENT -> 65.0;
                case BUG_FIX -> 35.0;
            };

            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("category", classification.category());
            if (classification.summary() != null) {
                metadata.put("summary", classification.summary());
            }

            return new ScoringResult("category", normalized, normalized,
                    "Category: %s (%.0f pts)".formatted(classification.category(), normalized),
                    metadata);
        } catch (Exception e) {
            LOG.warnf("LLM classification failed for PR #%d: %s", pr.number(), e.getMessage());
            return new ScoringResult("category", 0, 0, null);
        }
    }

    private PrClassification classifyWithRateLimit(PullRequestData pr) throws InterruptedException {
        String description = pr.description() != null ? pr.description() : "";

        llmSemaphore.acquire();
        try {
            return classifier.classify(pr.title(), description);
        } finally {
            llmSemaphore.release();
        }
    }
}
