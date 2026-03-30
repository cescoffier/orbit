package io.quarkus.orbit.pulse.scoring.rules;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.PrClassification;
import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class CategoryRule implements ScoringRule {

    private static final Logger LOG = Logger.getLogger(CategoryRule.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 4;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final PrClassifier classifier;
    private final Instant[] timestamps = new Instant[MAX_REQUESTS_PER_MINUTE];
    private int index = 0;

    CategoryRule(PrClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public ScoringResult evaluate(PullRequestData pr, PrPulseConfig.Rules rules) {
        try {
            PrCategory category = resolveCategory(pr);

            double points = switch (category) {
                case FEATURE -> rules.featureScore();
                case ENHANCEMENT -> rules.enhancementScore();
                case BUG_FIX -> rules.bugFixScore();
            };

            return new ScoringResult(points,
                    "Category: %s (%d pts)".formatted(category, (int) points));
        } catch (Exception e) {
            LOG.warnf("LLM classification failed for PR #%d: %s", pr.number(), e.getMessage());
            return new ScoringResult(0, null);
        }
    }

    private PrCategory resolveCategory(PullRequestData pr) {
        Optional<PrCategory> cached = lookupCached(pr);
        if (cached.isPresent()) {
            LOG.debugf("Using cached classification for PR #%d: %s", pr.number(), cached.get());
            return cached.get();
        }

        throttle();
        String description = pr.description() != null ? pr.description() : "";
        PrCategory category = classifier.classify(pr.title(), description);

        storeClassification(pr, category);
        return category;
    }

    @Transactional
    Optional<PrCategory> lookupCached(PullRequestData pr) {
        return PrClassification.findCategory(pr.repoIdentifier(), pr.number());
    }

    @Transactional
    void storeClassification(PullRequestData pr, PrCategory category) {
        PrClassification.store(pr.repoIdentifier(), pr.number(), category);
    }

    private synchronized void throttle() {
        Instant now = Instant.now();
        Instant oldest = timestamps[index];
        if (oldest != null) {
            Duration elapsed = Duration.between(oldest, now);
            if (elapsed.compareTo(WINDOW) < 0) {
                long sleepMs = WINDOW.minus(elapsed).toMillis() + 100;
                LOG.debugf("Rate limiting: sleeping %d ms", sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        timestamps[index] = Instant.now();
        index = (index + 1) % MAX_REQUESTS_PER_MINUTE;
    }
}
