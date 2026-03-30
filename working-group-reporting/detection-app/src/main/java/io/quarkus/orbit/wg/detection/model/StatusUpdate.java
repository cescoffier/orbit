package io.quarkus.orbit.wg.detection.model;

import java.time.Instant;

public record StatusUpdate(
        String id,
        String body,
        String bodyHTML,
        String status,
        Instant createdAt) {
}