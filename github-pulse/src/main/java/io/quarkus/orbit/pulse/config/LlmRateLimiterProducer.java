package io.quarkus.orbit.pulse.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.util.concurrent.Semaphore;

@ApplicationScoped
public class LlmRateLimiterProducer {

    @Produces
    @Singleton
    @Named("llmSemaphore")
    public Semaphore llmSemaphore() {
        return new Semaphore(15);
    }
}
