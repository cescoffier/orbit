package io.quarkus.orbit.wg.graphql;

import java.time.Instant;

public record StatusUpdate(
        String id,
        String body,
        String bodyHTML,
        String status,
        Instant createdAt) {
}