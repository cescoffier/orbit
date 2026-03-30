package io.quarkus.orbit.pulse.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "pr-pulse")
public interface PrPulseConfig {

    @WithDefault("60")
    int globalThreshold();

    String githubToken();

    @WithDefault("14")
    int lookbackDays();

    List<Repository> repositories();

    interface Repository {
        String name();
        String owner();
        Optional<String> description();
        Rules rules();
    }

    interface Rules {
        @WithDefault("0.5")
        double linesChangedWeight();

        @WithDefault("20")
        int criticalFilesBonus();

        @WithDefault("5")
        int commentActivityWeight();

        Optional<List<String>> criticalPaths();

        @WithDefault("100")
        int maxSizeScore();

        @WithDefault("30")
        int featureScore();

        @WithDefault("20")
        int enhancementScore();

        @WithDefault("10")
        int bugFixScore();
    }
}
