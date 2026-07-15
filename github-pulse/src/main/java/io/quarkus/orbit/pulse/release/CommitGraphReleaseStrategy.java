package io.quarkus.orbit.pulse.release;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.ReleasePullRequest;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves release PRs by walking the commit history reachable from the release tag and
 * collecting {@code associatedPullRequests} on each commit. Stops as soon as a commit whose
 * headline contains the previous release marker ("[maven-release-plugin] prepare release") is
 * encountered, so only commits that belong to this release window are considered.
 *
 * <p>Works best for repos that follow a standard merge-commit workflow (e.g. smallrye-graphql).
 */
@ApplicationScoped
public class CommitGraphReleaseStrategy implements ReleaseStrategy {

    private static final Logger LOG = Logger.getLogger(CommitGraphReleaseStrategy.class);

    /**
     * Commit headlines that signal the start of a previous release — stop walking when seen.
     * Covers the two most common release commit conventions in the SmallRye / Quarkus ecosystem:
     * <ul>
     *   <li>{@code [maven-release-plugin] prepare release X.Y.Z} — used by smallrye-graphql and others</li>
     *   <li>{@code chore(release): update version metadata for …} — used by smallrye-mutiny</li>
     * </ul>
     * The first commit in the history IS the release commit for the requested tag, so it is
     * skipped unconditionally; subsequent matching commits mark the previous release boundary.
     */
    private static final List<String> RELEASE_COMMIT_MARKERS = List.of(
            "[maven-release-plugin] prepare release",
            "[maven-release-plugin] prepare for next development iteration",
            "chore(release): update version metadata",
            "chore(release): set development version"
    );

    private final PrPulseConfig config;

    public CommitGraphReleaseStrategy(PrPulseConfig config) {
        this.config = config;
    }

    @Override
    public List<ReleasePullRequest> fetchPRsForRelease(String owner, String repo, String tagName) throws Exception {
        LOG.infof("Fetching PRs for %s/%s tag %s via commit graph", owner, repo, tagName);

        // Use a LinkedHashMap to preserve insertion order while deduplicating by PR number.
        Map<Integer, ReleasePullRequest> seen = new LinkedHashMap<>();
        String after = null;
        boolean stopFound = false;
        int page = 0;

        try (DynamicGraphQLClient client = buildClient()) {
            while (!stopFound) {
                page++;
                String query = buildQuery();
                Map<String, Object> vars = new java.util.HashMap<>();
                vars.put("owner", owner);
                vars.put("repo", repo);
                vars.put("tagName", tagName);
                vars.put("after", after);

                var response = client.executeSync(query, vars);
                if (response.hasError()) {
                    LOG.errorf("GraphQL errors fetching release commits for %s/%s@%s: %s",
                            owner, repo, tagName, response.getErrors());
                    break;
                }

                JsonObject data = response.getData();
                if (data == null) break;

                JsonObject repoObj = data.getJsonObject("repository");
                JsonObject releaseObj = repoObj != null ? repoObj.getJsonObject("release") : null;
                if (releaseObj == null) {
                    LOG.warnf("Release tag '%s' not found on %s/%s", tagName, owner, repo);
                    break;
                }

                // The tag target may be a Tag object (annotated tag) wrapping a Commit,
                // or a direct Commit (lightweight tag).
                JsonObject tagObj = releaseObj.getJsonObject("tag");
                JsonObject tagTarget = tagObj != null ? tagObj.getJsonObject("target") : null;
                JsonObject historyContainer = resolveHistory(tagTarget);
                if (historyContainer == null) {
                    LOG.warnf("Unable to resolve commit history for %s/%s@%s", owner, repo, tagName);
                    break;
                }

                JsonArray nodes = historyContainer.getJsonArray("nodes");
                LOG.debugf("Page %d: processing %d commits", page, nodes.size());

                for (JsonValue nodeVal : nodes) {
                    JsonObject commit = nodeVal.asJsonObject();
                    String headline = commit.getString("messageHeadline", "");

                    // The very first commit on the first page is the release commit itself — skip it.
                    // Any subsequent matching commit is the previous release boundary — stop there.
                    boolean isReleaseCommit = isReleaseMarker(headline);
                    if (isReleaseCommit && (page > 1 || !seen.isEmpty())) {
                        stopFound = true;
                        break;
                    }
                    if (isReleaseCommit) {
                        // first commit on page 1 — skip but continue walking
                        continue;
                    }

                    JsonObject aprs = commit.getJsonObject("associatedPullRequests");
                    if (aprs == null) continue;
                    JsonArray prNodes = aprs.getJsonArray("nodes");
                    if (prNodes == null) continue;

                    for (JsonValue prVal : prNodes) {
                        JsonObject pr = prVal.asJsonObject();
                        int number = pr.getInt("number");
                        if (!seen.containsKey(number)) {
                            JsonObject authorObj = pr.getJsonObject("author");
                            String author = authorObj != null ? authorObj.getString("login", "") : "";
                            seen.put(number, new ReleasePullRequest(
                                    number,
                                    pr.getString("title", ""),
                                    pr.getString("url", ""),
                                    author,
                                    pr.getString("mergedAt", null)
                            ));
                        }
                    }
                }

                JsonObject pageInfo = historyContainer.getJsonObject("pageInfo");
                boolean hasNextPage = pageInfo != null && pageInfo.getBoolean("hasNextPage", false);
                if (!hasNextPage) break;
                after = pageInfo.getString("endCursor", null);
            }
        }

        LOG.infof("Found %d unique PRs in %s/%s@%s (walked %d page(s))",
                seen.size(), owner, repo, tagName, page);
        return new ArrayList<>(seen.values());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isReleaseMarker(String headline) {
        for (String marker : RELEASE_COMMIT_MARKERS) {
            if (headline.startsWith(marker)) return true;
        }
        return false;
    }

    /**
     * Resolves the {@code history} container regardless of whether the tag target is an
     * annotated {@code Tag} object (which wraps a {@code Commit}) or a lightweight tag
     * pointing directly to a {@code Commit}.
     */
    private JsonObject resolveHistory(JsonObject tagTarget) {
        if (tagTarget == null) return null;
        // Annotated tag: Tag → target → Commit → history
        if (tagTarget.containsKey("target")) {
            JsonObject inner = tagTarget.getJsonObject("target");
            if (inner != null && inner.containsKey("history")) {
                return inner.getJsonObject("history");
            }
        }
        // Lightweight tag: direct Commit → history
        if (tagTarget.containsKey("history")) {
            return tagTarget.getJsonObject("history");
        }
        return null;
    }

    private String buildQuery() {
        return """
                query($owner: String!, $repo: String!, $tagName: String!, $after: String) {
                  repository(owner: $owner, name: $repo) {
                    release(tagName: $tagName) {
                      tag {
                        target {
                          ... on Tag {
                            target {
                              ... on Commit {
                                history(first: 100, after: $after) {
                                  nodes {
                                    messageHeadline
                                    associatedPullRequests(first: 10) {
                                      nodes {
                                        number
                                        title
                                        url
                                        mergedAt
                                        author { login }
                                      }
                                    }
                                  }
                                  pageInfo { hasNextPage endCursor }
                                }
                              }
                            }
                          }
                          ... on Commit {
                            history(first: 100, after: $after) {
                              nodes {
                                messageHeadline
                                associatedPullRequests(first: 10) {
                                  nodes {
                                    number
                                    title
                                    url
                                    mergedAt
                                    author { login }
                                  }
                                }
                              }
                              pageInfo { hasNextPage endCursor }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
    }

    private DynamicGraphQLClient buildClient() {
        return DynamicGraphQLClientBuilder.newBuilder()
                .url("https://api.github.com/graphql")
                .header("Authorization", "Bearer " + config.githubToken())
                .build();
    }
}
