package io.quarkus.orbit.pulse.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlatformReportInputTest {

    @Test
    void parsesYamlWithQuarkusVersionsAndReleases(@TempDir Path tempDir) throws Exception {
        Path yaml = tempDir.resolve("platform.yaml");
        Files.writeString(yaml, """
                quarkus-versions:
                  - 3.38.0.CR1
                  - 3.38.0

                releases:
                  smallrye-mutiny:
                    - 2.8.0
                  smallrye-stork:
                    - 2.10.0
                    - 2.10.1
                """);

        PlatformReportInput input = PlatformReportInput.fromYaml(yaml);

        assertEquals(List.of("3.38.0.CR1", "3.38.0"), input.quarkusVersions());
        assertEquals(Map.of(
                "smallrye-mutiny", List.of("2.8.0"),
                "smallrye-stork", List.of("2.10.0", "2.10.1")
        ), input.releases());
    }

    @Test
    void parsesYamlWithNoReleases(@TempDir Path tempDir) throws Exception {
        Path yaml = tempDir.resolve("platform.yaml");
        Files.writeString(yaml, """
                quarkus-versions:
                  - 3.38.0
                """);

        PlatformReportInput input = PlatformReportInput.fromYaml(yaml);

        assertEquals(List.of("3.38.0"), input.quarkusVersions());
        assertTrue(input.releases().isEmpty());
    }

    @Test
    void parsesYamlWithNoQuarkusVersions(@TempDir Path tempDir) throws Exception {
        Path yaml = tempDir.resolve("platform.yaml");
        Files.writeString(yaml, """
                releases:
                  smallrye-mutiny:
                    - 2.8.0
                """);

        PlatformReportInput input = PlatformReportInput.fromYaml(yaml);

        assertTrue(input.quarkusVersions().isEmpty());
        assertEquals(Map.of("smallrye-mutiny", List.of("2.8.0")), input.releases());
    }

    @Test
    void parsesEmptyYaml(@TempDir Path tempDir) throws Exception {
        Path yaml = tempDir.resolve("platform.yaml");
        Files.writeString(yaml, """
                # Only comments
                """);

        PlatformReportInput input = PlatformReportInput.fromYaml(yaml);

        assertTrue(input.quarkusVersions().isEmpty());
        assertTrue(input.releases().isEmpty());
    }
}
