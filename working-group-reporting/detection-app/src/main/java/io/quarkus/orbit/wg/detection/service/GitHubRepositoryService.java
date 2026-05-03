package io.quarkus.orbit.wg.detection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class GitHubRepositoryService {

    @GraphQLClient("github")
    DynamicGraphQLClient githubClient;

    @Inject
    ObjectMapper mapper;

    private Map<String, RepositoryData> cache = new ConcurrentHashMap<>();

    public RepositoryData get(String ownerAndName) {
        return cache.get(ownerAndName);
    }

    public void populate(String ownerAndName, Instant cutoffDate) {
        String[] parts = ownerAndName.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Repository must be in format 'owner/repo'");
        }
        String owner = parts[0];
        String name = parts[1];
        populate(owner, name, cutoffDate);
    }

    public void populate(String owner, String name, Instant cutoffDate) {
        String key = owner + "/" + name;
        cache.computeIfAbsent(key, k -> fetchRepositoryData(owner, name, cutoffDate));
    }

    private RepositoryData fetchRepositoryData(String owner, String name, Instant cutoffDate) {
        Log.infof("Fetching repository data for owner %s and name %s", owner, name);
        RepositoryData data = new RepositoryData(name, owner);
        var list = data.getIssuesAndPRs();
        list.addAll(fetchIssues(owner, name, cutoffDate));
        list.addAll(fetchPullRequests(owner, name, cutoffDate));

        Log.infof("Fetched %d issues and PRs for %s/%s", list.size(), owner, name);
        return data;

    }

    /**
     * Fetch issues from the repository
     */
    private List<IssueOrPR> fetchIssues(String owner, String repo, Instant cutoffDate) {

        List<IssueOrPR> issues = new ArrayList<>();

        String query = """
                query($owner: String!, $repo: String!, $cursor: String) {
                  repository(owner: $owner, name: $repo) {
                    issues(first: 100, after: $cursor, orderBy: {field: UPDATED_AT, direction: DESC}) {
                      nodes {
                        id
                        number
                        title
                        body
                        url
                        state
                        createdAt
                        updatedAt
                        labels(first: 10) {
                          nodes {
                            name
                          }
                        }
                      }
                      pageInfo {
                        hasNextPage
                        endCursor
                      }
                    }
                  }
                }
                """;

        String cursor = null;
        boolean hasNextPage = true;
        boolean shouldContinue = true;

        while (hasNextPage && shouldContinue) {
            Map<String, Object> variables = cursor == null
                    ? Map.of("owner", owner, "repo", repo)
                    : Map.of("owner", owner, "repo", repo, "cursor", cursor);

            Response response;
            try {
                response = githubClient.executeSync(query, variables);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (response.hasError()) {
                Log.warnf("Error fetching issues: %s", response.getErrors());
                break;
            }

            JsonObject repository = response.getData().getJsonObject("repository");
            JsonObject issuesObj = repository.getJsonObject("issues");
            JsonArray nodes = issuesObj.getJsonArray("nodes");

            for (JsonValue value : nodes) {
                JsonObject issue = value.asJsonObject();
                Instant updatedAt = Instant.parse(issue.getString("updatedAt"));

                // Stop if we've gone past the cutoff date
                if (updatedAt.isBefore(cutoffDate)) {
                    shouldContinue = false;
                    break;
                }

                List<String> labels = extractLabels(issue);

                IssueOrPR item = new IssueOrPR(
                        issue.getString("id"),  // GitHub node ID
                        owner,
                        repo,
                        issue.getInt("number"),
                        issue.getString("title"),
                        issue.getString("body", ""),
                        issue.getString("url"),
                        issue.getString("state"),
                        IssueOrPR.ItemType.ISSUE,
                        Instant.parse(issue.getString("createdAt")),
                        updatedAt,
                        labels
                );

                issues.add(item);
            }

            JsonObject pageInfo = issuesObj.getJsonObject("pageInfo");
            hasNextPage = pageInfo.getBoolean("hasNextPage", false);
            cursor = pageInfo.getString("endCursor", null);
        }

        return issues;
    }

    /**
     * Fetch pull requests from the repository
     */
    private List<IssueOrPR> fetchPullRequests(String owner, String repo, Instant cutoffDate) {

        List<IssueOrPR> prs = new ArrayList<>();

        String query = """
                query($owner: String!, $repo: String!, $cursor: String) {
                  repository(owner: $owner, name: $repo) {
                    pullRequests(first: 100, after: $cursor, orderBy: {field: UPDATED_AT, direction: DESC}) {
                      nodes {
                        id
                        number
                        title
                        body
                        url
                        state
                        createdAt
                        updatedAt
                        labels(first: 10) {
                          nodes {
                            name
                          }
                        }
                      }
                      pageInfo {
                        hasNextPage
                        endCursor
                      }
                    }
                  }
                }
                """;

        String cursor = null;
        boolean hasNextPage = true;
        boolean shouldContinue = true;

        while (hasNextPage && shouldContinue) {
            Map<String, Object> variables = cursor == null
                    ? Map.of("owner", owner, "repo", repo)
                    : Map.of("owner", owner, "repo", repo, "cursor", cursor);

            Response response;
            try {
                response = githubClient.executeSync(query, variables);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (response.hasError()) {
                Log.warnf("Error fetching pull requests: %s", response.getErrors());
                break;
            }

            JsonObject repository = response.getData().getJsonObject("repository");
            JsonObject prsObj = repository.getJsonObject("pullRequests");
            JsonArray nodes = prsObj.getJsonArray("nodes");

            for (JsonValue value : nodes) {
                JsonObject pr = value.asJsonObject();
                Instant updatedAt = Instant.parse(pr.getString("updatedAt"));

                // Stop if we've gone past the cutoff date
                if (updatedAt.isBefore(cutoffDate)) {
                    shouldContinue = false;
                    break;
                }

                List<String> labels = extractLabels(pr);

                IssueOrPR item = new IssueOrPR(
                        pr.getString("id"),  // GitHub node ID
                        owner,
                        repo,
                        pr.getInt("number"),
                        pr.getString("title"),
                        pr.getString("body", ""),
                        pr.getString("url"),
                        pr.getString("state"),
                        IssueOrPR.ItemType.PULL_REQUEST,
                        Instant.parse(pr.getString("createdAt")),
                        updatedAt,
                        labels
                );

                prs.add(item);
            }

            JsonObject pageInfo = prsObj.getJsonObject("pageInfo");
            hasNextPage = pageInfo.getBoolean("hasNextPage", false);
            cursor = pageInfo.getString("endCursor", null);
        }

        return prs;
    }


    private List<String> extractLabels(JsonObject node) {
        List<String> labels = new ArrayList<>();
        JsonObject labelsObj = node.getJsonObject("labels");
        if (labelsObj != null) {
            JsonArray labelNodes = labelsObj.getJsonArray("nodes");
            if (labelNodes != null) {
                for (JsonValue lv : labelNodes) {
                    labels.add(lv.asJsonObject().getString("name"));
                }
            }
        }
        return labels;
    }

    public static class RepositoryData {

        public final String name;
        public final String owner;
        public final List<IssueOrPR> issuesAndPRs = new CopyOnWriteArrayList<>();


        public RepositoryData(String name, String owner) {
            this.name = name;
            this.owner = owner;
        }

        public List<IssueOrPR> getIssuesAndPRs() {
            return issuesAndPRs;
        }

        public String name() {
            return owner + "/" + name;
        }


    }


}
