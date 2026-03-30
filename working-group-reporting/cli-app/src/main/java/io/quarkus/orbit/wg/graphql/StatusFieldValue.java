package io.quarkus.orbit.wg.graphql;

import java.time.Instant;

public record StatusFieldValue(
        String status,
        Instant updatedAt,
        Instant createdAt) {
}