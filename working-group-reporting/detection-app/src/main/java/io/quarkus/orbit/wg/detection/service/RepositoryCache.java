package io.quarkus.orbit.wg.detection.service;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Cache for repository data fetched from GitHub GraphQL.
 * Stores repository IDs and other metadata to reduce API calls.
 */
@Singleton
public class RepositoryCache {

    @GraphQLClient("github")
    DynamicGraphQLClient githubClient;

    private final Map<String, RepositoryData> cache = new ConcurrentHashMap<>();

    /**
     * Get repository data, fetching from GitHub if not cached.
     *
     * @param repository Repository in format "owner/repo"
     * @return Repository data
     */
    public RepositoryData getOrFetchRepository(String repository) {
        return cache.computeIfAbsent(repository, this::fetchRepository);
    }

    private RepositoryData fetchRepository(String repository) {
        String[] parts = repository.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Repository must be in format 'owner/repo'");
        }

        String owner = parts[0];
        String repo = parts[1];

        try {
            Log.debugf("Fetching repository data for %s/%s", owner, repo);

            String query = """
                query($owner: String!, $repo: String!) {
                  repository(owner: $owner, name: $repo) {
                    id
                    name
                    owner {
                      login
                    }
                    url
                    isPrivate
                  }
                }
                """;

            Map<String, Object> variables = Map.of("owner", owner, "repo", repo);
            Response response = githubClient.executeSync(query, variables);

            if (response.hasError()) {
                Log.warnf("Error fetching repository %s: %s", repository, response.getErrors());
                throw new RuntimeException("Failed to fetch repository: " + repository);
            }

            var repoData = response.getData().getJsonObject("repository");

            return new RepositoryData(
                repoData.getString("id"),
                owner,
                repo,
                repoData.getString("url"),
                repoData.getBoolean("isPrivate", false)
            );

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch repository " + repository, e);
        }
    }

    /**
     * Clear the cache
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Repository data from GitHub
     */
    public record RepositoryData(
            String id,
            String owner,
            String name,
            String url,
            boolean isPrivate) {

        public String fullName() {
            return owner + "/" + name;
        }
    }
}
