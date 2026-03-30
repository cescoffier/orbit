package io.quarkus.orbit.pulse.graphql;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClientBuilder;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class GitHubGraphQLClient {

    private static final Logger LOG = Logger.getLogger(GitHubGraphQLClient.class);

    private final PrPulseConfig config;
    private final Semaphore semaphore = new Semaphore(5);

    public GitHubGraphQLClient(PrPulseConfig config) {
        this.config = config;
    }

    public List<PullRequestData> fetchMergedPRs(String owner, String repoName) throws Exception {
        semaphore.acquire();
        try {
            return doFetch(owner, repoName);
        } finally {
            semaphore.release();
        }
    }

    private List<PullRequestData> doFetch(String owner, String repoName) throws Exception {
        String sinceDate = LocalDate.now().minusDays(config.lookbackDays())
                .format(DateTimeFormatter.ISO_LOCAL_DATE);

        String query = """
                query($searchQuery: String!) {
                  search(query: $searchQuery, type: ISSUE, first: 50) {
                    nodes {
                      ... on PullRequest {
                        number
                        title
                        url
                        body
                        additions
                        deletions
                        author {
                          login
                        }
                        comments {
                          totalCount
                        }
                        labels(first: 10) {
                          nodes {
                            name
                          }
                        }
                        files(first: 100) {
                          nodes {
                            path
                          }
                        }
                      }
                    }
                  }
                }
                """;

        String searchQuery = "repo:%s/%s is:pr is:merged merged:>=%s".formatted(owner, repoName, sinceDate);

        try (DynamicGraphQLClient client = DynamicGraphQLClientBuilder.newBuilder()
                .url("https://api.github.com/graphql")
                .header("Authorization", "Bearer " + config.githubToken())
                .build()) {

            var response = client.executeSync(query, java.util.Map.of("searchQuery", searchQuery));

            if (response.hasError()) {
                LOG.errorf("GraphQL errors for %s/%s: %s", owner, repoName, response.getErrors());
                return List.of();
            }

            JsonObject data = response.getData();
            if (data == null) {
                return List.of();
            }

            JsonArray nodes = data.getJsonObject("search").getJsonArray("nodes");
            List<PullRequestData> results = new ArrayList<>();

            for (JsonValue node : nodes) {
                JsonObject pr = node.asJsonObject();
                if (pr.isEmpty()) continue;

                List<String> filePaths = new ArrayList<>();
                JsonObject filesObj = pr.getJsonObject("files");
                if (filesObj != null) {
                    JsonArray fileNodes = filesObj.getJsonArray("nodes");
                    if (fileNodes != null) {
                        for (JsonValue f : fileNodes) {
                            filePaths.add(f.asJsonObject().getString("path"));
                        }
                    }
                }

                List<String> labels = new ArrayList<>();
                JsonObject labelsObj = pr.getJsonObject("labels");
                if (labelsObj != null) {
                    JsonArray labelNodes = labelsObj.getJsonArray("nodes");
                    if (labelNodes != null) {
                        for (JsonValue l : labelNodes) {
                            labels.add(l.asJsonObject().getString("name"));
                        }
                    }
                }

                String author = "";
                JsonObject authorObj = pr.getJsonObject("author");
                if (authorObj != null) {
                    author = authorObj.getString("login", "");
                }

                results.add(new PullRequestData(
                        owner,
                        repoName,
                        pr.getInt("number"),
                        pr.getString("title", ""),
                        pr.getString("url", ""),
                        author,
                        pr.getString("body", ""),
                        pr.getInt("additions", 0),
                        pr.getInt("deletions", 0),
                        pr.getJsonObject("comments").getInt("totalCount", 0),
                        filePaths,
                        labels
                ));
            }

            LOG.infof("Fetched %d merged PRs from %s/%s", results.size(), owner, repoName);
            return results;
        }
    }
}
