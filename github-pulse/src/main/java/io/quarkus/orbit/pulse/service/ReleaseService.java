package io.quarkus.orbit.pulse.service;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.ReleasePullRequest;
import io.quarkus.orbit.pulse.release.CommitGraphReleaseStrategy;
import io.quarkus.orbit.pulse.release.ReleaseNotesReleaseStrategy;
import io.quarkus.orbit.pulse.release.ReleaseStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the PRs included in a given release tag for a configured repository,
 * delegating to the strategy configured for that repository.
 */
@ApplicationScoped
public class ReleaseService {

    private static final Logger LOG = Logger.getLogger(ReleaseService.class);

    private final PrPulseConfig config;
    private final CommitGraphReleaseStrategy commitGraphStrategy;
    private final ReleaseNotesReleaseStrategy releaseNotesStrategy;

    public ReleaseService(PrPulseConfig config,
                          CommitGraphReleaseStrategy commitGraphStrategy,
                          ReleaseNotesReleaseStrategy releaseNotesStrategy) {
        this.config = config;
        this.commitGraphStrategy = commitGraphStrategy;
        this.releaseNotesStrategy = releaseNotesStrategy;
    }

    /**
     * Returns the PRs included in {@code tagName} for the given repository identifier
     * ({@code "owner/name"} or plain {@code "name"}).
     *
     * @throws IllegalArgumentException if the repository is not configured
     */
    public List<ReleasePullRequest> fetchPRsForRelease(String repoIdentifier, String tagName) throws Exception {
        PrPulseConfig.Repository repo = findRepoConfig(repoIdentifier)
                .orElseThrow(() -> new IllegalArgumentException("Unknown repository: " + repoIdentifier));

        ReleaseStrategy strategy = resolveStrategy(repo.releaseStrategy());
        LOG.infof("Using strategy %s for %s/%s@%s", repo.releaseStrategy(), repo.owner(), repo.name(), tagName);

        return strategy.fetchPRsForRelease(repo.owner(), repo.name(), tagName);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ReleaseStrategy resolveStrategy(PrPulseConfig.ReleaseStrategy strategyEnum) {
        return switch (strategyEnum) {
            case COMMIT_GRAPH -> commitGraphStrategy;
            case RELEASE_NOTES -> releaseNotesStrategy;
        };
    }

    public Optional<PrPulseConfig.Repository> findRepoConfig(String repoIdentifier) {
        return config.repositories().stream()
                .filter(r -> r.name().equals(repoIdentifier)
                        || (r.owner() + "/" + r.name()).equals(repoIdentifier))
                .findFirst();
    }

    public List<String> knownRepos() {
        return config.repositories().stream()
                .map(r -> r.owner() + "/" + r.name())
                .toList();
    }
}
