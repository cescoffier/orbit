package io.quarkus.orbit.pulse.graphql;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClientBuilder;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.PullRequestData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return fetchMergedPRs(owner, repoName, config.lookbackDays());
    }

    public List<PullRequestData> fetchMergedPRs(String owner, String repoName, int lookbackDays) throws Exception {
        semaphore.acquire();
        try {
            return doFetch(owner, repoName, lookbackDays);
        } finally {
            semaphore.release();
        }
    }

    private List<PullRequestData> doFetch(String owner, String repoName, int lookbackDays) throws Exception {
        String sinceDate = LocalDate.now().minusDays(lookbackDays)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);

        String query = """
                query($searchQuery: String!, $after: String) {
                  search(query: $searchQuery, type: ISSUE, first: 50, after: $after) {
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
                    pageInfo {
                      hasNextPage
                      endCursor
                    }
                  }
                }
                """;

        String searchQuery = "repo:%s/%s is:pr is:merged merged:>=%s".formatted(owner, repoName, sinceDate);
        List<PullRequestData> allResults = new ArrayList<>();
        String after = null;

        try (DynamicGraphQLClient client = DynamicGraphQLClientBuilder.newBuilder()
                .url("https://api.github.com/graphql")
                .header("Authorization", "Bearer " + config.githubToken())
                .build()) {

            boolean hasMore = true;
            while (hasMore) {
                Map<String, Object> vars = new java.util.HashMap<>();
                vars.put("searchQuery", searchQuery);
                vars.put("after", after);

                var response = client.executeSync(query, vars);

                if (response.hasError()) {
                    LOG.errorf("GraphQL errors for %s/%s: %s", owner, repoName, response.getErrors());
                    break;
                }

                JsonObject data = response.getData();
                if (data == null) break;

                JsonObject search = data.getJsonObject("search");
                JsonArray nodes = search.getJsonArray("nodes");

                for (JsonValue node : nodes) {
                    JsonObject pr = node.asJsonObject();
                    if (pr.isEmpty()) continue;
                    allResults.add(parsePullRequest(owner, repoName, pr));
                }

                JsonObject pageInfo = search.getJsonObject("pageInfo");
                hasMore = pageInfo != null && pageInfo.getBoolean("hasNextPage", false);
                if (hasMore) {
                    after = pageInfo.getString("endCursor", null);
                }
            }
        }

        LOG.infof("Fetched %d merged PRs from %s/%s", allResults.size(), owner, repoName);
        return allResults;
    }

    /**
     * Fetches full PR data for the given PR numbers using batched GraphQL queries.
     *
     * @param owner     GitHub repository owner
     * @param repoName  GitHub repository name
     * @param prNumbers List of PR numbers to enrich
     * @return List of fully enriched PullRequestData records
     */
    public List<PullRequestData> fetchPullRequestsByNumbers(String owner, String repoName, List<Integer> prNumbers)
            throws Exception {
        if (prNumbers.isEmpty()) return List.of();

        List<PullRequestData> results = new ArrayList<>();
        Set<Integer> resolvedNumbers = new HashSet<>();

        int batchSize = 25;
        for (int i = 0; i < prNumbers.size(); i += batchSize) {
            List<Integer> batch = prNumbers.subList(i, Math.min(i + batchSize, prNumbers.size()));

            semaphore.acquire();
            try {
                List<PullRequestData> batchResults = fetchPrBatch(owner, repoName, batch);
                results.addAll(batchResults);
                batchResults.forEach(pr -> resolvedNumbers.add(pr.number()));
            } finally {
                semaphore.release();
            }
        }

        // Numbers that weren't PRs may be issues — find the PRs that closed them
        List<Integer> unresolvedNumbers = prNumbers.stream()
                .filter(n -> !resolvedNumbers.contains(n))
                .toList();
        if (!unresolvedNumbers.isEmpty()) {
            LOG.infof("%d numbers not found as PRs, resolving as issues to find closing PRs...",
                    unresolvedNumbers.size());
            for (int i = 0; i < unresolvedNumbers.size(); i += batchSize) {
                List<Integer> batch = unresolvedNumbers.subList(i,
                        Math.min(i + batchSize, unresolvedNumbers.size()));
                semaphore.acquire();
                try {
                    for (PullRequestData pr : fetchClosingPrsForIssues(owner, repoName, batch)) {
                        if (resolvedNumbers.add(pr.number())) {
                            results.add(pr);
                        }
                    }
                } finally {
                    semaphore.release();
                }
            }
        }

        LOG.infof("Enriched %d PRs from %s/%s", results.size(), owner, repoName);
        return results;
    }

    public String fetchReleaseBody(String owner, String repo, String tag) throws Exception {
        String url = "https://api.github.com/repos/%s/%s/releases/tags/%s"
                .formatted(owner, repo, tag);

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + config.githubToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new IllegalArgumentException(
                        "Release tag '%s' not found for %s/%s".formatted(tag, owner, repo));
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "GitHub API error %d fetching release %s for %s/%s: %s"
                                .formatted(response.statusCode(), tag, owner, repo, response.body()));
            }

            JsonObject release = Json.createReader(new java.io.StringReader(response.body())).readObject();
            return release.getString("body", "");
        }
    }

    public String fetchPreviousReleaseTag(String owner, String repo, String tag) throws Exception {
        int[] targetVersion = parseVersion(tag);
        int page = 1;

        try (HttpClient client = HttpClient.newHttpClient()) {
            while (page <= 10) {
                String url = "https://api.github.com/repos/%s/%s/releases?per_page=50&page=%d"
                        .formatted(owner, repo, page);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + config.githubToken())
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOG.warnf("GitHub API error %d listing releases for %s/%s", response.statusCode(), owner, repo);
                    return null;
                }

                JsonArray releases = Json.createReader(new java.io.StringReader(response.body())).readArray();
                if (releases.isEmpty()) break;

                for (JsonObject release : releases.getValuesAs(JsonObject.class)) {
                    if (release.getBoolean("draft", false)) continue;

                    String tagName = release.getString("tag_name", "");
                    int[] candidateVersion = parseVersion(tagName);
                    if (compareVersions(candidateVersion, targetVersion) < 0) {
                        return tagName;
                    }
                }

                page++;
            }
        }

        LOG.warnf("No previous release with lower version found for %s/%s tag %s", owner, repo, tag);
        return null;
    }

    static int[] parseVersion(String tag) {
        String cleaned = tag.replaceFirst("^[vV]?", "")
                .replaceFirst("[.-](Final|RELEASE|GA|release)$", "");
        String[] parts = cleaned.split("[.]");
        int[] version = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                version[i] = Integer.parseInt(parts[i].replaceFirst("[^0-9].*", ""));
            } catch (NumberFormatException e) {
                version[i] = 0;
            }
        }
        return version;
    }

    static int compareVersions(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private List<PullRequestData> fetchPrBatch(String owner, String repoName, List<Integer> numbers) throws Exception {
        StringBuilder queryBuilder = new StringBuilder("query($owner: String!, $repo: String!) {\n");
        queryBuilder.append("  repository(owner: $owner, name: $repo) {\n");

        for (int j = 0; j < numbers.size(); j++) {
            queryBuilder.append("    pr%d: pullRequest(number: %d) {\n".formatted(j, numbers.get(j)));
            queryBuilder.append("      number title url body additions deletions\n");
            queryBuilder.append("      author { login }\n");
            queryBuilder.append("      comments { totalCount }\n");
            queryBuilder.append("      labels(first: 10) { nodes { name } }\n");
            queryBuilder.append("      files(first: 100) { nodes { path } }\n");
            queryBuilder.append("    }\n");
        }

        queryBuilder.append("  }\n}\n");

        try (DynamicGraphQLClient client = DynamicGraphQLClientBuilder.newBuilder()
                .url("https://api.github.com/graphql")
                .header("Authorization", "Bearer " + config.githubToken())
                .build()) {

            var response = client.executeSync(queryBuilder.toString(),
                    Map.of("owner", owner, "repo", repoName));

            if (response.hasError()) {
                LOG.warnf("GraphQL partial errors enriching PRs for %s/%s: %s", owner, repoName, response.getErrors());
            }

            JsonObject data = response.getData();
            if (data == null) return List.of();

            JsonObject repoObj = data.getJsonObject("repository");
            List<PullRequestData> results = new ArrayList<>();

            for (int j = 0; j < numbers.size(); j++) {
                String key = "pr" + j;
                if (!repoObj.containsKey(key) || repoObj.isNull(key)) continue;
                JsonObject pr = repoObj.getJsonObject(key);
                if (pr.isEmpty()) continue;
                results.add(parsePullRequest(owner, repoName, pr));
            }

            return results;
        }
    }

    private List<PullRequestData> fetchClosingPrsForIssues(String owner, String repoName,
                                                              List<Integer> issueNumbers) throws Exception {
        StringBuilder queryBuilder = new StringBuilder("query($owner: String!, $repo: String!) {\n");
        queryBuilder.append("  repository(owner: $owner, name: $repo) {\n");

        for (int j = 0; j < issueNumbers.size(); j++) {
            queryBuilder.append("    issue%d: issue(number: %d) {\n".formatted(j, issueNumbers.get(j)));
            queryBuilder.append("      timelineItems(itemTypes: [CLOSED_EVENT], first: 10) {\n");
            queryBuilder.append("        nodes {\n");
            queryBuilder.append("          ... on ClosedEvent {\n");
            queryBuilder.append("            closer {\n");
            queryBuilder.append("              ... on PullRequest {\n");
            queryBuilder.append("                number title url body additions deletions\n");
            queryBuilder.append("                author { login }\n");
            queryBuilder.append("                comments { totalCount }\n");
            queryBuilder.append("                labels(first: 10) { nodes { name } }\n");
            queryBuilder.append("                files(first: 100) { nodes { path } }\n");
            queryBuilder.append("              }\n");
            queryBuilder.append("            }\n");
            queryBuilder.append("          }\n");
            queryBuilder.append("        }\n");
            queryBuilder.append("      }\n");
            queryBuilder.append("    }\n");
        }

        queryBuilder.append("  }\n}\n");

        try (DynamicGraphQLClient client = DynamicGraphQLClientBuilder.newBuilder()
                .url("https://api.github.com/graphql")
                .header("Authorization", "Bearer " + config.githubToken())
                .build()) {

            var response = client.executeSync(queryBuilder.toString(),
                    Map.of("owner", owner, "repo", repoName));

            if (response.hasError()) {
                LOG.warnf("GraphQL errors resolving issues for %s/%s: %s",
                        owner, repoName, response.getErrors());
            }

            JsonObject data = response.getData();
            if (data == null) return List.of();

            JsonObject repoObj = data.getJsonObject("repository");
            List<PullRequestData> results = new ArrayList<>();

            for (int j = 0; j < issueNumbers.size(); j++) {
                String key = "issue" + j;
                if (!repoObj.containsKey(key) || repoObj.isNull(key)) continue;
                JsonObject issue = repoObj.getJsonObject(key);

                JsonObject timelineItems = issue.getJsonObject("timelineItems");
                if (timelineItems == null) continue;

                JsonArray nodes = timelineItems.getJsonArray("nodes");
                if (nodes == null) continue;

                for (JsonValue nodeVal : nodes) {
                    if (nodeVal.getValueType() != JsonValue.ValueType.OBJECT) continue;
                    JsonObject node = nodeVal.asJsonObject();
                    if (!node.containsKey("closer") || node.isNull("closer")) continue;
                    JsonObject closer = node.getJsonObject("closer");
                    if (closer.containsKey("number")) {
                        results.add(parsePullRequest(owner, repoName, closer));
                    }
                }
            }

            LOG.infof("Resolved %d closing PRs from %d issues in %s/%s",
                    results.size(), issueNumbers.size(), owner, repoName);
            return results;
        }
    }

    /**
     * Parses a PR JSON object into a PullRequestData record.
     * Shared between doFetch, fetchPrBatch, and fetchClosingPrsForIssues.
     */
    private PullRequestData parsePullRequest(String owner, String repoName, JsonObject pr) {
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

        return new PullRequestData(
                owner,
                repoName,
                pr.getInt("number"),
                pr.getString("title", ""),
                pr.getString("url", ""),
                author,
                pr.getString("body", ""),
                pr.getInt("additions", 0),
                pr.getInt("deletions", 0),
                pr.getJsonObject("comments") != null ? pr.getJsonObject("comments").getInt("totalCount", 0) : 0,
                filePaths,
                labels
        );
    }
}
