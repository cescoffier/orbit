package io.quarkus.orbit.wg.graphql;

import java.time.Instant;

public record Content(
        String id,
        String title,
        int number,
        String url,
        Instant updatedAt,
        Instant createdAt) {
}