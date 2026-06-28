package io.quarkus.orbit.monday.service.support;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Semaphore;

@ApplicationScoped
public class LlmSemaphoreProducer {

    @ConfigProperty(name = "llm-max-concurrency", defaultValue = "5")
    int maxConcurrency;

    @Produces
    @Singleton
    @Named("llmSemaphore")
    public Semaphore llmSemaphore() {
        return new Semaphore(maxConcurrency);
    }
}
