package io.quarkus.orbit.wg.detection.model;

import java.time.Instant;

public record Content(
        String id,
        String title,
        int number,
        String url,
        Instant updatedAt,
        Instant createdAt) {
}