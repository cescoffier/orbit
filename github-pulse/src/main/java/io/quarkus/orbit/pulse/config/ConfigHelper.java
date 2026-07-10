package io.quarkus.orbit.pulse.config;

import java.util.List;
import java.util.Optional;

/**
 * Utility class for PrPulseConfig lookups.
 */
public class ConfigHelper {

    private ConfigHelper() {
        // Utility class
    }

    /**
     * Find a repository configuration by name or "owner/name".
     */
    public static Optional<PrPulseConfig.Repository> findRepoConfig(PrPulseConfig config, String repoIdentifier) {
        return config.repositories().stream()
                .filter(r -> r.name().equals(repoIdentifier)
                        || (r.owner() + "/" + r.name()).equals(repoIdentifier))
                .findFirst();
    }

    /**
     * Get a list of all known repository identifiers in "owner/name" format.
     */
    public static List<String> knownRepos(PrPulseConfig config) {
        return config.repositories().stream()
                .map(r -> r.owner() + "/" + r.name())
                .toList();
    }
}
