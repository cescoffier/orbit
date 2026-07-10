package io.quarkus.orbit.pulse.release;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
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
public class CommitGraphReleaseStrategy {

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

    public List<Integer> fetchPRsBetweenTags(String owner, String repo, String fromTag, String toTag) throws Exception {
        LOG.infof("Fetching PRs for %s/%s between tags %s..%s via commit graph", owner, repo, fromTag, toTag);

        // Resolve fromTag to its commit SHA so we know when to stop walking
        String fromCommitSha = resolveTagCommitSha(owner, repo, fromTag);
        if (fromCommitSha == null) {
            LOG.warnf("Could not resolve from-tag '%s' on %s/%s, falling back to release markers", fromTag, owner, repo);
            fromCommitSha = "";
        }

        // Use a LinkedHashMap to preserve insertion order while deduplicating by PR number.
        Map<Integer, Boolean> seen = new LinkedHashMap<>();
        String after = null;
        boolean stopFound = false;
        int page = 0;

        try (DynamicGraphQLClient client = buildClient()) {
            while (!stopFound) {
                page++;
                String query = buildBetweenTagsQuery();
                Map<String, Object> vars = new java.util.HashMap<>();
                vars.put("owner", owner);
                vars.put("repo", repo);
                vars.put("tagName", toTag);
                vars.put("after", after);

                var response = client.executeSync(query, vars);
                if (response.hasError()) {
                    LOG.errorf("GraphQL errors fetching release commits for %s/%s %s..%s: %s",
                            owner, repo, fromTag, toTag, response.getErrors());
                    break;
                }

                JsonObject data = response.getData();
                if (data == null) break;

                JsonObject repoObj = safeGetObject(data, "repository");
                JsonObject releaseObj = repoObj != null ? safeGetObject(repoObj, "release") : null;
                if (releaseObj == null) {
                    LOG.warnf("Release tag '%s' not found on %s/%s", toTag, owner, repo);
                    break;
                }

                // The tag target may be a Tag object (annotated tag) wrapping a Commit,
                // or a direct Commit (lightweight tag).
                JsonObject tagObj = safeGetObject(releaseObj, "tag");
                JsonObject tagTarget = tagObj != null ? safeGetObject(tagObj, "target") : null;
                JsonObject historyContainer = resolveHistory(tagTarget);
                if (historyContainer == null) {
                    LOG.warnf("Unable to resolve commit history for %s/%s@%s", owner, repo, toTag);
                    break;
                }

                JsonArray nodes = historyContainer.getJsonArray("nodes");
                LOG.debugf("Page %d: processing %d commits", page, nodes.size());

                for (JsonValue nodeVal : nodes) {
                    JsonObject commit = nodeVal.asJsonObject();
                    String oid = commit.getString("oid", "");
                    String headline = commit.getString("messageHeadline", "");

                    // Stop when we reach the from-tag commit (primary stop condition)
                    if (!fromCommitSha.isEmpty() && oid.equals(fromCommitSha)) {
                        LOG.debugf("Reached from-tag commit %s at page %d", oid.substring(0, 8), page);
                        stopFound = true;
                        break;
                    }

                    // Also check release markers as fallback (skip first commit, stop on subsequent)
                    if (isReleaseMarker(headline) && !seen.isEmpty()) {
                        LOG.debugf("Reached release marker '%s' at page %d", headline, page);
                        stopFound = true;
                        break;
                    }

                    JsonObject aprs = commit.getJsonObject("associatedPullRequests");
                    if (aprs == null) continue;
                    JsonArray prNodes = aprs.getJsonArray("nodes");
                    if (prNodes == null) continue;

                    for (JsonValue prVal : prNodes) {
                        JsonObject pr = prVal.asJsonObject();
                        int number = pr.getInt("number");
                        seen.putIfAbsent(number, true);
                    }
                }

                JsonObject pageInfo = historyContainer.getJsonObject("pageInfo");
                boolean hasNextPage = pageInfo != null && pageInfo.getBoolean("hasNextPage", false);
                if (!hasNextPage) break;
                after = pageInfo.getString("endCursor", null);
            }
        }

        LOG.infof("Found %d unique PRs in %s/%s between %s..%s (%d pages)",
                seen.size(), owner, repo, fromTag, toTag, page);
        return new ArrayList<>(seen.keySet());
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
            JsonObject inner = safeGetObject(tagTarget, "target");
            if (inner != null && inner.containsKey("history")) {
                return safeGetObject(inner, "history");
            }
        }
        // Lightweight tag: direct Commit → history
        if (tagTarget.containsKey("history")) {
            return safeGetObject(tagTarget, "history");
        }
        return null;
    }

    private static JsonObject safeGetObject(JsonObject parent, String key) {
        JsonValue value = parent.get(key);
        return value != null && value.getValueType() == JsonValue.ValueType.OBJECT
                ? value.asJsonObject() : null;
    }

    /**
     * Resolves a tag name to its target commit SHA using GitHub GraphQL API.
     * Returns null if the tag cannot be resolved.
     */
    private String resolveTagCommitSha(String owner, String repo, String tagName) {
        String query = """
                query($owner: String!, $repo: String!, $tagName: String!) {
                  repository(owner: $owner, name: $repo) {
                    release(tagName: $tagName) {
                      tag {
                        target {
                          ... on Tag {
                            target {
                              ... on Commit { oid }
                            }
                          }
                          ... on Commit { oid }
                        }
                      }
                    }
                  }
                }
                """;

        try (DynamicGraphQLClient client = buildClient()) {
            var response = client.executeSync(query, Map.of("owner", owner, "repo", repo, "tagName", tagName));
            if (response.hasError()) {
                LOG.warnf("GraphQL errors resolving tag %s on %s/%s: %s", tagName, owner, repo, response.getErrors());
                return null;
            }

            JsonObject data = response.getData();
            if (data == null) return null;

            JsonObject repoObj = safeGetObject(data, "repository");
            JsonObject releaseObj = repoObj != null ? safeGetObject(repoObj, "release") : null;
            if (releaseObj == null) return null;

            JsonObject tagObj = safeGetObject(releaseObj, "tag");
            JsonObject tagTarget = tagObj != null ? safeGetObject(tagObj, "target") : null;
            if (tagTarget == null) return null;

            // Handle both annotated tags (Tag → target → Commit) and lightweight tags (direct Commit)
            if (tagTarget.containsKey("target")) {
                JsonObject inner = safeGetObject(tagTarget, "target");
                return inner != null ? inner.getString("oid", null) : null;
            }
            return tagTarget.getString("oid", null);
        } catch (Exception e) {
            LOG.warnf("Failed to resolve tag %s on %s/%s: %s", tagName, owner, repo, e.getMessage());
            return null;
        }
    }

    private String buildBetweenTagsQuery() {
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
                                    oid
                                    messageHeadline
                                    associatedPullRequests(first: 10) {
                                      nodes {
                                        number
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
                                oid
                                messageHeadline
                                associatedPullRequests(first: 10) {
                                  nodes {
                                    number
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
