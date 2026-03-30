package io.quarkus.orbit.wg.graphql;

public record PageInfo(String endCursor, boolean hasNextPage) {
}