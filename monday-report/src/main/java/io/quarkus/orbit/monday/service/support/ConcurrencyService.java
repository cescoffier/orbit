package io.quarkus.orbit.monday.service.support;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class ConcurrencyService {

    @ConfigProperty(name = "task-max-concurrency", defaultValue = "100")
    int maxConcurrency;

    private ExecutorService executor;
    private Semaphore semaphore;

    public void init() {
        if (executor == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
            semaphore = new Semaphore(maxConcurrency);
            Log.infof("🔒 Initialized concurrency service with max concurrency: %d", maxConcurrency);
        }
    }

    public <T> Uni<T> submit(String taskName, Callable<T> task) {
        init();
        return Uni.createFrom().item(() -> {
            try {
                semaphore.acquire();
                Log.infof("▶️  Starting task: %s", taskName);
                T result = task.call();
                Log.infof("✅ Completed task: %s", taskName);
                return result;
            } catch (Exception e) {
                Log.errorf(e, "❌ Failed task: %s", taskName);
                throw new RuntimeException("Task failed: " + taskName, e);
            } finally {
                semaphore.release();
            }
        }).runSubscriptionOn(executor);
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
