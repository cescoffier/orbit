package io.quarkus.orbit.pulse.release;

import io.quarkus.orbit.pulse.model.ReleasePullRequest;

import java.util.List;

/**
 * Resolves the list of pull requests included in a specific release tag for a repository.
 */
public interface ReleaseStrategy {

    /**
     * @param owner   GitHub repository owner
     * @param repo    GitHub repository name
     * @param tagName release tag (e.g. "3.37.2" or "3.0.0.Beta5")
     * @return deduplicated, ordered list of PRs included in this release
     */
    List<ReleasePullRequest> fetchPRsForRelease(String owner, String repo, String tagName) throws Exception;
}
