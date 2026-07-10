package io.quarkus.orbit.pulse.model;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PlatformReportInput(
        List<String> quarkusVersions,
        Map<String, List<String>> releases
) {
    @SuppressWarnings("unchecked")
    public static PlatformReportInput fromYaml(Path path) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> data;
        try (var inputStream = Files.newInputStream(path)) {
            data = yaml.load(inputStream);
        }
        if (data == null) {
            data = Map.of();
        }

        List<String> versions = data.containsKey("quarkus-versions")
                ? ((List<Object>) data.get("quarkus-versions")).stream().map(Object::toString).toList()
                : List.of();

        Map<String, List<String>> releases = new LinkedHashMap<>();
        if (data.containsKey("releases")) {
            Map<String, List<Object>> raw = (Map<String, List<Object>>) data.get("releases");
            for (var entry : raw.entrySet()) {
                releases.put(entry.getKey(), entry.getValue().stream().map(Object::toString).toList());
            }
        }

        return new PlatformReportInput(versions, releases);
    }
}
