package io.quarkus.orbit.pulse.release;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.ReleasePullRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves release PRs by parsing the structured markdown changelog embedded in the
 * GitHub release body, as fetched from the GitHub REST API.
 *
 * <p>Expects list items of the form:
 * <pre>* [#NNN](https://github.com/OWNER/REPO/pull/NNN) - Title</pre>
 *
 * <p>Works best for repos that embed a full changelog in their release notes
 * (e.g. Quarkus, which uses a backport-PR model making commit-graph traversal unreliable).
 */
@ApplicationScoped
public class ReleaseNotesReleaseStrategy implements ReleaseStrategy {

    private static final Logger LOG = Logger.getLogger(ReleaseNotesReleaseStrategy.class);

    /**
     * Matches: {@code * [#NNN](https://github.com/OWNER/REPO/pull/NNN) - Title}
     * Group 1 = PR number, Group 2 = full URL, Group 3 = title.
     */
    private static final Pattern PR_LINE = Pattern.compile(
            "^\\*\\s+\\[#(\\d+)]\\((https://github\\.com/[^/]+/[^/]+/pull/(\\d+))\\)\\s+-\\s+(.+)$",
            Pattern.MULTILINE
    );

    private static final String GITHUB_API = "https://api.github.com";

    private final PrPulseConfig config;

    public ReleaseNotesReleaseStrategy(PrPulseConfig config) {
        this.config = config;
    }

    @Override
    public List<ReleasePullRequest> fetchPRsForRelease(String owner, String repo, String tagName) throws Exception {
        LOG.infof("Fetching PRs for %s/%s tag %s via release notes", owner, repo, tagName);

        String body = fetchReleaseBody(owner, repo, tagName);
        if (body == null || body.isBlank()) {
            LOG.warnf("Release body is empty for %s/%s@%s — no PRs extracted", owner, repo, tagName);
            return List.of();
        }

        List<ReleasePullRequest> prs = new ArrayList<>();
        Matcher m = PR_LINE.matcher(body);
        while (m.find()) {
            int number = Integer.parseInt(m.group(1));
            String url = m.group(2);
            String title = m.group(4).trim();
            prs.add(new ReleasePullRequest(number, title, url, "", null));
        }

        LOG.infof("Extracted %d PRs from release notes of %s/%s@%s", prs.size(), owner, repo, tagName);
        return prs;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String fetchReleaseBody(String owner, String repo, String tagName) throws Exception {
        String url = "%s/repos/%s/%s/releases/tags/%s".formatted(GITHUB_API, owner, repo, tagName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.githubToken())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                LOG.warnf("Release tag '%s' not found on %s/%s", tagName, owner, repo);
                return null;
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("GitHub API error %d for %s/%s@%s: %s"
                        .formatted(response.statusCode(), owner, repo, tagName, response.body()));
            }

            JsonObject json = Json.createReader(new java.io.StringReader(response.body())).readObject();
            return json.getString("body", "");
        }
    }
}
