package io.quarkus.orbit.wg.detection.model;

import java.util.List;

public record StatusUpdates(List<StatusUpdate> nodes, PageInfo pageInfo) {
}