package io.quarkus.orbit.pulse.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionParsingTest {

    @Test
    void parseSimpleVersion() {
        assertArrayEquals(new int[]{2, 9, 0}, GitHubGraphQLClient.parseVersion("2.9.0"));
    }

    @Test
    void parseVersionWithPrefix() {
        assertArrayEquals(new int[]{1, 5, 3}, GitHubGraphQLClient.parseVersion("v1.5.3"));
    }

    @Test
    void parseVersionWithFinalSuffix() {
        assertArrayEquals(new int[]{2, 6, 0}, GitHubGraphQLClient.parseVersion("2.6.0.Final"));
    }

    @Test
    void compareVersionsLower() {
        assertTrue(GitHubGraphQLClient.compareVersions(
                GitHubGraphQLClient.parseVersion("2.8.0"),
                GitHubGraphQLClient.parseVersion("2.9.0")) < 0);
    }

    @Test
    void compareVersionsHigher() {
        assertTrue(GitHubGraphQLClient.compareVersions(
                GitHubGraphQLClient.parseVersion("2.10.0"),
                GitHubGraphQLClient.parseVersion("2.9.0")) > 0);
    }

    @Test
    void compareVersionsEqual() {
        assertEquals(0, GitHubGraphQLClient.compareVersions(
                GitHubGraphQLClient.parseVersion("3.18.0"),
                GitHubGraphQLClient.parseVersion("3.18.0")));
    }

    @Test
    void compareVersionsDifferentLength() {
        assertTrue(GitHubGraphQLClient.compareVersions(
                GitHubGraphQLClient.parseVersion("2.8"),
                GitHubGraphQLClient.parseVersion("2.9.0")) < 0);
    }

    @Test
    void patchReleaseAfterMinorDoesNotMatch() {
        // 2.8.1 < 2.9.0 — correct previous tag for 2.9.0
        assertTrue(GitHubGraphQLClient.compareVersions(
                GitHubGraphQLClient.parseVersion("2.8.1"),
                GitHubGraphQLClient.parseVersion("2.9.0")) < 0);
    }

    @Test
    void newerMinorIsNotPrevious() {
        // 2.10.0 > 2.9.1 — should NOT be picked as previous for 2.9.1
        assertTrue(GitHubGraphQLClient.compareVersions(
                GitHubGraphQLClient.parseVersion("2.10.0"),
                GitHubGraphQLClient.parseVersion("2.9.1")) > 0);
    }
}
