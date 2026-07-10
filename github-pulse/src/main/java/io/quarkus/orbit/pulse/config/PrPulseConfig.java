package io.quarkus.orbit.pulse.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "pr-pulse")
public interface PrPulseConfig {

    @WithDefault("40")
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
        @WithDefault("0.20")
        double sizeWeight();

        @WithDefault("0.35")
        double categoryWeight();

        @WithDefault("0.25")
        double criticalPathWeight();

        @WithDefault("0.20")
        double commentWeight();

        Optional<List<String>> criticalPaths();

        @WithDefault("100")
        int maxSizeScore();

        @WithDefault("8.1")
        double sizeScaleFactor();
    }
}
