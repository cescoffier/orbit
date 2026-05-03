package io.quarkus.orbit.wg.detection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

@QuarkusTest
class BatchClassifierTest {

    @Inject
    BatchClassifier batchClassifier;

    @InjectMock
    IssueClassifier issueClassifier;

    static IssueOrPR issue(String owner, String repo, int number, String title, String body) {
        return new IssueOrPR(
                "node-" + number, owner, repo, number, title, body,
                "https://github.com/" + owner + "/" + repo + "/issues/" + number,
                "OPEN", IssueOrPR.ItemType.ISSUE, Instant.now(), Instant.now(), List.of()
        );
    }

    static IssueOrPR pr(String owner, String repo, int number, String title, String body) {
        return new IssueOrPR(
                "node-pr-" + number, owner, repo, number, title, body,
                "https://github.com/" + owner + "/" + repo + "/pull/" + number,
                "OPEN", IssueOrPR.ItemType.PULL_REQUEST, Instant.now(), Instant.now(), List.of()
        );
    }

    @Test
    void issueAssignedToSingleBestMatchingWG() {
        var observabilityIssue = issue("quarkusio", "quarkus", 1,
                "Add OpenTelemetry metrics for REST endpoints",
                "We need to expose OTel metrics from REST endpoints for better observability");
        var dataIssue = issue("quarkusio", "quarkus", 2,
                "Hibernate ORM session handling improvement",
                "Improve session handling for Panache entities with reactive datasources");
        var generalIssue = issue("quarkusio", "quarkus", 3,
                "Fix NullPointerException in config parser",
                "NPE when parsing empty config values");

        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - Observability.Next", "Improve observability with OpenTelemetry, metrics, and tracing");
        wgProposals.put("WG - Quarkus Data", "Improve Hibernate ORM, Panache, and reactive datasources");
        wgProposals.put("WG - Graceful Shutdown", "Implement graceful shutdown for Quarkus applications");

        String aiResponse = """
                {"results": {
                    "quarkusio/quarkus#1": "WG - Observability.Next",
                    "quarkusio/quarkus#2": "WG - Quarkus Data",
                    "quarkusio/quarkus#3": ""
                }}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        List<IssueOrPR> candidates = List.of(observabilityIssue, dataIssue, generalIssue);
        Map<String, List<IssueOrPR>> results = batchClassifier.classifyBatch(wgProposals, candidates, 50);

        assertThat(results.get("WG - Observability.Next")).containsExactly(observabilityIssue);
        assertThat(results.get("WG - Quarkus Data")).containsExactly(dataIssue);
        assertThat(results.get("WG - Graceful Shutdown")).isEmpty();
    }

    @Test
    void issueNotAssignedToMultipleWGs() {
        var issue1 = issue("quarkusio", "quarkus", 10,
                "Add tracing support for reactive messaging",
                "OpenTelemetry tracing for SmallRye Reactive Messaging channels");

        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - Observability.Next", "Improve observability with OpenTelemetry, metrics, and tracing");
        wgProposals.put("WG - Unified Event Bus", "Unify messaging with SmallRye Reactive Messaging");

        String aiResponse = """
                {"results": {"quarkusio/quarkus#10": "WG - Observability.Next"}}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        Map<String, List<IssueOrPR>> results = batchClassifier.classifyBatch(
                wgProposals, List.of(issue1), 50);

        assertThat(results.get("WG - Observability.Next")).containsExactly(issue1);
        assertThat(results.get("WG - Unified Event Bus")).isEmpty();
    }

    @Test
    void emptyCandidatesReturnsEmptyResults() {
        Map<String, String> wgProposals = Map.of("WG - Test", "test proposal");
        Map<String, List<IssueOrPR>> results = batchClassifier.classifyBatch(
                wgProposals, List.of(), 50);

        assertThat(results.get("WG - Test")).isEmpty();
    }

    @Test
    void unknownWGNameInResponseIsTreatedAsNoMatch() {
        var issue1 = issue("quarkusio", "quarkus", 5,
                "Some issue", "Some description");

        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - Observability.Next", "Observability proposal");

        String aiResponse = """
                {"results": {"quarkusio/quarkus#5": "WG - NonExistent Group"}}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        Map<String, List<IssueOrPR>> results = batchClassifier.classifyBatch(
                wgProposals, List.of(issue1), 50);

        assertThat(results.get("WG - Observability.Next")).isEmpty();
    }

    @Test
    void missingItemInResponseDefaultsToNoMatch() {
        var issue1 = issue("quarkusio", "quarkus", 1, "Issue 1", "desc");
        var issue2 = issue("quarkusio", "quarkus", 2, "Issue 2", "desc");

        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - Test", "Test proposal");

        String aiResponse = """
                {"results": {"quarkusio/quarkus#1": "WG - Test"}}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        Map<String, List<IssueOrPR>> results = batchClassifier.classifyBatch(
                wgProposals, List.of(issue1, issue2), 50);

        assertThat(results.get("WG - Test")).containsExactly(issue1);
    }

    @Test
    void markdownCodeBlocksInResponseAreHandled() {
        var issue1 = issue("quarkusio", "quarkus", 7, "OTel issue", "desc");

        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - Observability.Next", "Observability proposal");

        String aiResponse = """
                ```json
                {"results": {"quarkusio/quarkus#7": "WG - Observability.Next"}}
                ```""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        Map<String, List<IssueOrPR>> results = batchClassifier.classifyBatch(
                wgProposals, List.of(issue1), 50);

        assertThat(results.get("WG - Observability.Next")).containsExactly(issue1);
    }

    @Test
    void batchingProcessesMultipleBatches() {
        var issue1 = issue("quarkusio", "quarkus", 1, "Issue 1", "desc");
        var issue2 = issue("quarkusio", "quarkus", 2, "Issue 2", "desc");
        var issue3 = issue("quarkusio", "quarkus", 3, "Issue 3", "desc");

        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - A", "Proposal A");
        wgProposals.put("WG - B", "Proposal B");

        // First batch (issues 1,2)
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString()))
                .thenReturn("""
                        {"results": {"quarkusio/quarkus#1": "WG - A", "quarkusio/quarkus#2": "WG - B"}}""")
                .thenReturn("""
                        {"results": {"quarkusio/quarkus#3": "WG - A"}}""");

        Map<String, List<IssueOrPR>> results = batchClassifier.classifyBatch(
                wgProposals, List.of(issue1, issue2, issue3), 2);

        assertThat(results.get("WG - A")).containsExactly(issue1, issue3);
        assertThat(results.get("WG - B")).containsExactly(issue2);
    }

    @Test
    void proposalsTextIncludesAllWGs() {
        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - Alpha", "Alpha proposal text");
        wgProposals.put("WG - Beta", "Beta proposal text");

        String text = batchClassifier.buildWorkingGroupProposalsText(wgProposals);

        assertThat(text).contains("### WG - Alpha");
        assertThat(text).contains("Alpha proposal text");
        assertThat(text).contains("### WG - Beta");
        assertThat(text).contains("Beta proposal text");
    }

    @Test
    void nullProposalIsReplacedWithPlaceholder() {
        Map<String, String> wgProposals = new LinkedHashMap<>();
        wgProposals.put("WG - NoReadme", null);

        String text = batchClassifier.buildWorkingGroupProposalsText(wgProposals);

        assertThat(text).contains("### WG - NoReadme");
        assertThat(text).contains("(No proposal document available)");
    }
}
