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
            PrCategory category = classifyWithRateLimit(pr);

            double points = switch (category) {
                case FEATURE -> rules.featureScore();
                case ENHANCEMENT -> rules.enhancementScore();
                case BUG_FIX -> rules.bugFixScore();
            };

            return new ScoringResult(points,
                    "Category: %s (%d pts)".formatted(category, (int) points),
                    Map.of("category", category));
        } catch (Exception e) {
            LOG.warnf("LLM classification failed for PR #%d: %s", pr.number(), e.getMessage());
            return new ScoringResult(0, null);
        }
    }

    private PrCategory classifyWithRateLimit(PullRequestData pr) throws InterruptedException {
        String description = pr.description() != null ? pr.description() : "";

        llmSemaphore.acquire();
        try {
            return classifier.classify(pr.title(), description);
        } finally {
            llmSemaphore.release();
        }
    }
}
