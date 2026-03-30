package io.quarkus.orbit.wg.graphql;

import java.util.List;

public record StatusUpdates(List<StatusUpdate> nodes, PageInfo pageInfo) {
}