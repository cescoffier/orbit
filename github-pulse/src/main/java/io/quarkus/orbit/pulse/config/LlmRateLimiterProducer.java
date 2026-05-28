package io.quarkus.orbit.pulse.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

import java.util.concurrent.Semaphore;

@ApplicationScoped
public class LlmRateLimiterProducer {

    @Produces
    @ApplicationScoped
    @Named("llmSemaphore")
    public Semaphore llmSemaphore() {
        return new Semaphore(15);
    }
}
