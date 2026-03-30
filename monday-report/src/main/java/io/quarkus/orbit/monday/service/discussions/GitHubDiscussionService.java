package io.quarkus.orbit.monday.service.discussions;

import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.config.MondayReportConfig;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GitHubDiscussionService {

    @Inject
    MondayReportConfig config;

    public List<Discussion> fetchDiscussions(String repoName) {
        String[] parts = repoName.split("/");
        String owner = parts[0];
        String repo = parts[1];

        String graphqlQuery = """
            query($owner: String!, $repo: String!, $first: Int!) {
              repository(owner: $owner, name: $repo) {
                discussions(first: $first, orderBy: {field: UPDATED_AT, direction: DESC}) {
                  nodes {
                    number
                    title
                    createdAt
                    updatedAt
                    url
                    body
                    comments(first: 50) {
                      nodes {
                        author {
                          login
                        }
                        body
                        createdAt
                      }
                    }
                  }
                }
              }
            }
            """;

        try (DynamicGraphQLClient client = DynamicGraphQLClientBuilder.newBuilder()
                .url("https://api.github.com/graphql")
                .header("Authorization", "Bearer " + config.githubToken())
                .build()) {

            var response = client.executeSync(graphqlQuery,
                    Map.of("owner", owner, "repo", repo, "first", 20));

            if (response.hasError()) {
                Log.warnf("GraphQL error for %s: %s", repoName, response.getErrors());
                return List.of();
            }

            JsonObject data = response.getData();
            if (data == null || data.isNull("repository")) {
                return List.of();
            }

            JsonObject repository = data.getJsonObject("repository");
            JsonObject discussionsObj = repository.getJsonObject("discussions");
            JsonArray nodes = discussionsObj.getJsonArray("nodes");

            List<Discussion> discussions = new ArrayList<>();
            LocalDate oneWeekAgo = LocalDate.now().minusWeeks(1);

            for (int i = 0; i < nodes.size(); i++) {
                JsonObject discussionNode = nodes.getJsonObject(i);
                int number = discussionNode.getInt("number");
                String title = discussionNode.getString("title");
                String createdAt = discussionNode.getString("createdAt");
                String updatedAt = discussionNode.getString("updatedAt");
                String url = discussionNode.getString("url");
                String body = discussionNode.getString("body", "");

                LocalDate updated = LocalDate.parse(updatedAt.substring(0, 10));

                // Only include discussions updated in the last week
                if (updated.isAfter(oneWeekAgo)) {
                    // Parse comments
                    List<DiscussionComment> comments = new ArrayList<>();
                    JsonObject commentsObj = discussionNode.getJsonObject("comments");
                    if (commentsObj != null && !commentsObj.isNull("nodes")) {
                        JsonArray commentNodes = commentsObj.getJsonArray("nodes");
                        for (int j = 0; j < commentNodes.size(); j++) {
                            JsonObject commentNode = commentNodes.getJsonObject(j);
                            String author = commentNode.getJsonObject("author").getString("login", "unknown");
                            String commentBody = commentNode.getString("body", "");
                            String commentCreatedAt = commentNode.getString("createdAt");

                            comments.add(new DiscussionComment(author, commentBody, commentCreatedAt));
                        }
                    }

                    discussions.add(new Discussion(number, title, url, body, createdAt, updatedAt, comments));
                }
            }

            return discussions;
        } catch (Exception e) {
            Log.warnf(e, "Failed to fetch discussions for %s", repoName);
            return List.of();
        }
    }
}
