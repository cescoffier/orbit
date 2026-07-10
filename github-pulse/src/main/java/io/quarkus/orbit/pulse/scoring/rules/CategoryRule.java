package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.PrClassification;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
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
            PrCategory category = classifyWithRateLimit(pr);

            double normalized = switch (category) {
                case FEATURE -> 100.0;
                case ENHANCEMENT -> 65.0;
                case BUG_FIX -> 35.0;
            };

            return new ScoringResult("category", normalized, normalized,
                    "Category: %s (%.0f pts)".formatted(category, normalized),
                    Map.of("category", category));
        } catch (Exception e) {
            LOG.warnf("LLM classification failed for PR #%d: %s", pr.number(), e.getMessage());
            return new ScoringResult("category", 0, 0, null);
        }
    }

    private PrCategory classifyWithRateLimit(PullRequestData pr) throws InterruptedException {
        try {
            Optional<PrCategory> cached = PrClassification.findCategory(pr.repoIdentifier(), pr.number());
            if (cached.isPresent()) {
                LOG.debugf("Using cached classification for PR #%d: %s", pr.number(), cached.get());
                return cached.get();
            }
        } catch (Exception e) {
            LOG.debugf("Cache lookup failed for PR #%d, classifying via LLM: %s", pr.number(), e.getMessage());
        }

        String description = pr.description() != null ? pr.description() : "";

        llmSemaphore.acquire();
        try {
            return classifier.classify(pr.title(), description);
        } finally {
            llmSemaphore.release();
        }
    }
}
