package io.quarkus.orbit.wg.detection.model;

import java.time.Instant;

public record StatusFieldValue(
        String status,
        Instant updatedAt,
        Instant createdAt) {
}