package io.quarkus.orbit.pulse.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ConfigValidator {

    private static final Logger LOG = Logger.getLogger(ConfigValidator.class);

    private final PrPulseConfig config;

    public ConfigValidator(PrPulseConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ev) {
        for (PrPulseConfig.Repository repo : config.repositories()) {
            if (repo.artifacts().isEmpty() || repo.artifacts().get().isEmpty()) {
                LOG.warnf("Repository %s/%s has no artifacts configured — invisible to BOM diffing",
                        repo.owner(), repo.name());
            }
        }
    }
}
