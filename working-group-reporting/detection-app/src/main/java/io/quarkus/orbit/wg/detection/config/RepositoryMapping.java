package io.quarkus.orbit.wg.detection.config;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;

/**
 * Configuration for mapping Working Groups to their repositories.
 * This is a manual mapping until repository information is added to the WG registry.
 */
@ConfigMapping(prefix = "working-groups")
public interface RepositoryMapping {

    /**
     * Map of Working Group name to list of repositories (in format "owner/repo")
     * Example:
     * working-groups:
     *   repository-mapping:
     *     Observability:
     *       - quarkusio/quarkus
     *       - quarkusio/quarkus-extensions
     */
    @ConfigDocMapKey("working-group-name")
    Map<String, List<String>> repositoryMapping();

    /**
     * Organizations to scan for working group projects
     */
    @WithDefault("quarkusio,quarkiverse")
    List<String> organizations();
}
