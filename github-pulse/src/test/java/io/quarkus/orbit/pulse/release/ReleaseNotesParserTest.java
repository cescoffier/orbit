package io.quarkus.orbit.pulse.release;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseNotesParserTest {

    @Test
    void parsesStandardPullRequestLines() {
        String body = """
                ## Changelog
                * [#1234](https://github.com/quarkusio/quarkus/pull/1234) - Fix startup issue
                * [#5678](https://github.com/quarkusio/quarkus/pull/5678) - Add new feature
                Some other text
                """;
        List<Integer> result = ReleaseNotesParser.parsePrNumbers(body);
        assertEquals(List.of(1234, 5678), result);
    }

    @Test
    void parsesIssueStyleLinks() {
        String body = "* [#42](https://github.com/owner/repo/issues/42) - A fix\n";
        List<Integer> result = ReleaseNotesParser.parsePrNumbers(body);
        assertEquals(List.of(42), result);
    }

    @Test
    void returnsEmptyForNoMatches() {
        List<Integer> result = ReleaseNotesParser.parsePrNumbers("No PRs here.");
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForBlankBody() {
        assertTrue(ReleaseNotesParser.parsePrNumbers("").isEmpty());
        assertTrue(ReleaseNotesParser.parsePrNumbers("   ").isEmpty());
    }
}
