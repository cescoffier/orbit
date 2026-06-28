package io.quarkus.orbit.monday.service.support;

import io.quarkus.logging.Log;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

public final class LlmRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 10_000;

    private LlmRetry() {
    }

    public static <T> T withRetry(Semaphore semaphore, Callable<T> action, String label) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for LLM semaphore: " + label, e);
        }
        try {
            return executeWithRetry(action, label);
        } finally {
            semaphore.release();
        }
    }

    private static <T> T executeWithRetry(Callable<T> action, String label) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS || !isRateLimitError(e)) {
                    throw wrap(e);
                }
                long delayMs = extractRetryDelay(e);
                Log.warnf("Rate limited on %s (attempt %d/%d), retrying in %dms",
                        label, attempt, MAX_ATTEMPTS, delayMs);
                sleep(delayMs);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    static boolean isRateLimitError(Throwable e) {
        if (e instanceof WebApplicationException wae) {
            return wae.getResponse().getStatus() == 429;
        }
        if (e instanceof ProcessingException && e.getCause() != null) {
            return isRateLimitError(e.getCause());
        }
        String message = e.getMessage();
        return message != null && message.contains("429");
    }

    static long extractRetryDelay(Throwable e) {
        String message = fullMessage(e);
        if (message != null) {
            var matcher = java.util.regex.Pattern.compile("retry in ([\\d.]+)s")
                    .matcher(message.toLowerCase());
            if (matcher.find()) {
                return (long) (Double.parseDouble(matcher.group(1)) * 1000);
            }
        }
        return DEFAULT_RETRY_DELAY_MS;
    }

    private static String fullMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(" ");
            }
            current = current.getCause();
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during rate limit backoff", e);
        }
    }

    private static RuntimeException wrap(Exception e) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(e);
    }
}
