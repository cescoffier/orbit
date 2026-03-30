package io.quarkus.orbit.monday.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.config.MondayReportConfig;
import io.quarkus.orbit.monday.service.support.ActivityMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;

/**
 * Service for interacting with GitHub's GraphQL API
 * This allows fetching all needed data in a single request, avoiding secondary rate limits
 */
@ApplicationScoped
public class GitHubGraphQLService {

    private static final String GITHUB_GRAPHQL_URL = "https://api.github.com/graphql";
    private static final int PAGE_SIZE = 100; // Max items per page
    private static final Set<String> EXCLUDED_LABELS = Set.of("area/dependencies", "area/documentation");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    MondayReportConfig config;

    private String token;

    @PostConstruct
    void init() {
        this.token = config.githubToken();
    }

    /**
     * Fetch Quarkus area activity using GraphQL
     * Returns a map of area labels to their activity metrics
     */
    public Map<String, ActivityMetrics> fetchQuarkusAreaActivity(LocalDate startDate, LocalDate endDate) throws Exception {
        Log.infof("🔍 Fetching Quarkus issues via GraphQL for %s to %s", startDate, endDate);

        Map<String, ActivityMetrics> areaCounts = new HashMap<>();

        // Fetch issues created in the period
        String createdQuery = String.format("repo:quarkusio/quarkus created:%s..%s", startDate, endDate);
        processQuarkusIssues(createdQuery, areaCounts, (area, metrics) ->
            new ActivityMetrics(metrics.created() + 1, metrics.closed(), metrics.openAtEnd())
        );

        // Fetch issues closed in the period
        String closedQuery = String.format("repo:quarkusio/quarkus closed:%s..%s", startDate, endDate);
        processQuarkusIssues(closedQuery, areaCounts, (area, metrics) ->
            new ActivityMetrics(metrics.created(), metrics.closed() + 1, metrics.openAtEnd())
        );

        // Fetch issues open at end of period
        String openQuery = String.format("repo:quarkusio/quarkus is:open created:..%s", endDate);
        processQuarkusIssues(openQuery, areaCounts, (area, metrics) ->
            new ActivityMetrics(metrics.created(), metrics.closed(), metrics.openAtEnd() + 1)
        );

        Log.infof("   Extracted %d area labels", areaCounts.size());
        return areaCounts;
    }

    /**
     * Process Quarkus issues and update area counts
     */
    private void processQuarkusIssues(String query, Map<String, ActivityMetrics> areaCounts,
                                     MetricsUpdater updater) throws Exception {
        List<JsonNode> allIssues = searchIssuesWithPagination(query);

        for (JsonNode issue : allIssues) {
            Set<String> areaLabels = new HashSet<>();
            JsonNode labelsNode = issue.path("labels").path("nodes");

            if (labelsNode.isArray()) {
                for (JsonNode label : labelsNode) {
                    String labelName = label.path("name").asText();
                    if (labelName.startsWith("area/") && !EXCLUDED_LABELS.contains(labelName)) {
                        areaLabels.add(labelName);
                    }
                }
            }

            // Update metrics for each area label on this issue
            for (String area : areaLabels) {
                ActivityMetrics current = areaCounts.getOrDefault(area, new ActivityMetrics());
                areaCounts.put(area, updater.update(area, current));
            }
        }
    }

    /**
     * Fetch Quarkiverse repository activity using GraphQL
     * Returns a map of repository names to their activity metrics
     */
    public Map<String, ActivityMetrics> fetchQuarkiverseActivity(LocalDate startDate, LocalDate endDate) throws Exception {
        Log.infof("🔍 Fetching Quarkiverse issues via GraphQL for %s to %s", startDate, endDate);

        Map<String, ActivityMetrics> repoCounts = new HashMap<>();

        // Fetch issues created in the period
        String createdQuery = String.format("org:quarkiverse created:%s..%s", startDate, endDate);
        processQuarkiverseIssues(createdQuery, repoCounts, (repo, metrics) ->
            new ActivityMetrics(metrics.created() + 1, metrics.closed(), metrics.openAtEnd())
        );

        // Fetch issues closed in the period
        String closedQuery = String.format("org:quarkiverse closed:%s..%s", startDate, endDate);
        processQuarkiverseIssues(closedQuery, repoCounts, (repo, metrics) ->
            new ActivityMetrics(metrics.created(), metrics.closed() + 1, metrics.openAtEnd())
        );

        // Fetch issues open at end of period
        String openQuery = String.format("org:quarkiverse is:open created:..%s", endDate);
        processQuarkiverseIssues(openQuery, repoCounts, (repo, metrics) ->
            new ActivityMetrics(metrics.created(), metrics.closed(), metrics.openAtEnd() + 1)
        );

        Log.infof("   Found activity in %d Quarkiverse repositories", repoCounts.size());
        return repoCounts;
    }

    /**
     * Process Quarkiverse issues and update repository counts
     */
    private void processQuarkiverseIssues(String query, Map<String, ActivityMetrics> repoCounts,
                                         MetricsUpdater updater) throws Exception {
        List<JsonNode> allIssues = searchIssuesWithPagination(query);

        for (JsonNode issue : allIssues) {
            String repoName = issue.path("repository").path("name").asText();
            if (!repoName.isEmpty()) {
                ActivityMetrics current = repoCounts.getOrDefault(repoName, new ActivityMetrics());
                repoCounts.put(repoName, updater.update(repoName, current));
            }
        }
    }

    /**
     * Search issues with pagination to get all results
     */
    private List<JsonNode> searchIssuesWithPagination(String searchQuery) throws Exception {
        List<JsonNode> allIssues = new ArrayList<>();
        String cursor = null;
        boolean hasNextPage = true;
        int pageCount = 0;

        while (hasNextPage) {
            pageCount++;
            JsonNode response = executeSearchQuery(searchQuery, cursor);

            JsonNode search = response.path("data").path("search");
            JsonNode nodes = search.path("nodes");
            JsonNode pageInfo = search.path("pageInfo");

            if (nodes.isArray()) {
                nodes.forEach(allIssues::add);
            }

            hasNextPage = pageInfo.path("hasNextPage").asBoolean(false);
            cursor = pageInfo.path("endCursor").asText(null);

            Log.infof("   Fetched page %d: %d items (total: %d, hasNextPage: %s)",
                pageCount, nodes.size(), allIssues.size(), hasNextPage);

            // Safety check to avoid infinite loops
            if (pageCount > 100) {
                Log.warn("   Stopping pagination after 100 pages");
                break;
            }
        }

        return allIssues;
    }

    /**
     * Execute a GraphQL search query
     */
    private JsonNode executeSearchQuery(String searchQuery, String cursor) throws Exception {
        String graphqlQuery = buildSearchQuery(searchQuery, cursor);

        String requestBody = objectMapper.writeValueAsString(Map.of("query", graphqlQuery));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(GITHUB_GRAPHQL_URL))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("GraphQL request failed with status " + response.statusCode() + ": " + response.body());
        }

        JsonNode jsonResponse = objectMapper.readTree(response.body());

        // Check for GraphQL errors
        if (jsonResponse.has("errors")) {
            throw new RuntimeException("GraphQL errors: " + jsonResponse.get("errors"));
        }

        return jsonResponse;
    }

    /**
     * Build the GraphQL search query
     * Fetches both issues and pull requests with all necessary fields
     */
    private String buildSearchQuery(String searchQuery, String cursor) {
        String afterClause = cursor != null ? ", after: \"" + cursor + "\"" : "";

        return """
            {
              search(query: "%s", type: ISSUE, first: %d%s) {
                issueCount
                pageInfo {
                  hasNextPage
                  endCursor
                }
                nodes {
                  ... on Issue {
                    title
                    repository {
                      name
                    }
                    labels(first: 50) {
                      nodes {
                        name
                      }
                    }
                  }
                  ... on PullRequest {
                    title
                    repository {
                      name
                    }
                    labels(first: 50) {
                      nodes {
                        name
                      }
                    }
                  }
                }
              }
            }
            """.formatted(searchQuery, PAGE_SIZE, afterClause);
    }

    /**
     * Functional interface for updating metrics
     */
    @FunctionalInterface
    private interface MetricsUpdater {
        ActivityMetrics update(String key, ActivityMetrics current);
    }
}
