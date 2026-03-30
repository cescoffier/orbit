package io.quarkus.orbit.monday.service;


import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.config.MondayReportConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.RateLimitChecker;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@ApplicationScoped
public class GithubService {

    @ConfigProperty(name = "github.ratelimit.log-interval", defaultValue = "60000")
    long rateLimitLogInterval; // Log rate limit status every N milliseconds

    @Inject
    MondayReportConfig config;

    private GitHub github;
    private long lastRateLimitLog = 0;

    @PostConstruct
    void init() {
        try {
            this.github = new GitHubBuilder()
                    .withOAuthToken(config.githubToken())
                    .withRateLimitChecker(new WaitingRateLimitChecker())
                    .build();

            // Log initial rate limit status
            logRateLimitStatus();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize GitHub client", e);
        }
    }

    public GitHub get() {
        // Periodically log rate limit status
        long now = System.currentTimeMillis();
        if (now - lastRateLimitLog > rateLimitLogInterval) {
            logRateLimitStatus();
            lastRateLimitLog = now;
        }

        return this.github;
    }

    public void logRateLimitStatus() {
        try {
            GHRateLimit rateLimit = github.getRateLimit();
            GHRateLimit.Record core = rateLimit.getCore();
            GHRateLimit.Record search = rateLimit.getSearch();
            GHRateLimit.Record graphql = rateLimit.getGraphQL();

            Log.infof("GitHub API Rate Limits:");
            Log.infof("   Core API    - %d/%d (resets at %s)",
                    core.getRemaining(), core.getLimit(), formatResetTime(core.getResetDate()));
            Log.infof("   Search API  - %d/%d (resets at %s)",
                    search.getRemaining(), search.getLimit(), formatResetTime(search.getResetDate()));
            Log.infof("   GraphQL API - %d/%d (resets at %s)",
                    graphql.getRemaining(), graphql.getLimit(), formatResetTime(graphql.getResetDate()));

        } catch (IOException e) {
            Log.warnf(e, "Failed to retrieve rate limit information");
        }
    }

    private String formatResetTime(Date resetDate) {
        return Instant.ofEpochMilli(resetDate.getTime())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }


    private static class WaitingRateLimitChecker extends RateLimitChecker {

        @Override
        protected boolean checkRateLimit(GHRateLimit.Record rateLimitRecord, long count) throws InterruptedException {
            if (rateLimitRecord.getRemaining() < 10) {
                Log.warnf("Approaching GitHub API Rate Limit - Remaining: %d/%d - going to wait a bit (%d s)", rateLimitRecord.getRemaining(), rateLimitRecord.getLimit(),
                        rateLimitRecord.getResetEpochSeconds() - Instant.now().getEpochSecond());
                sleepUntilReset(rateLimitRecord);
                return true;
            }
            return false;
        }
    }
}
