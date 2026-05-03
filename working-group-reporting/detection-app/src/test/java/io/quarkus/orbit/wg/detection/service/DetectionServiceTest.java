package io.quarkus.orbit.wg.detection.service;

import io.quarkus.orbit.wg.detection.model.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;

@QuarkusTest
class DetectionServiceTest {

    @Inject
    DetectionService detectionService;

    @InjectMock
    WorkingGroupService workingGroupService;

    @InjectMock
    GitHubRepositoryService repositoryService;

    @InjectMock
    IssueClassifier issueClassifier;

    // -- Fake data builders --

    static IssueOrPR issue(int number, String title, String body) {
        return issue("quarkusio", "quarkus", number, title, body);
    }

    static IssueOrPR issue(String owner, String repo, int number, String title, String body) {
        return new IssueOrPR(
                "node-" + number, owner, repo, number, title, body,
                "https://github.com/" + owner + "/" + repo + "/issues/" + number,
                "OPEN", IssueOrPR.ItemType.ISSUE, Instant.now(), Instant.now(), List.of()
        );
    }

    static IssueOrPR issueWithLabels(int number, String title, String body, List<String> labels) {
        return new IssueOrPR(
                "node-" + number, "quarkusio", "quarkus", number, title, body,
                "https://github.com/quarkusio/quarkus/issues/" + number,
                "OPEN", IssueOrPR.ItemType.ISSUE, Instant.now(), Instant.now(), labels
        );
    }

    static WorkingGroupBoard wg(String name, String proposal) {
        return wg(name, proposal, List.of());
    }

    static WorkingGroupBoard wg(String name, String proposal, List<Item> existingItems) {
        return new WorkingGroupBoard(
                "project-" + name.hashCode(),
                name,
                proposal,
                "https://github.com/orgs/quarkusio/projects/1",
                Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now(),
                new StatusUpdates(List.of(
                        new StatusUpdate("su-1", "On track", "", "ON_TRACK", Instant.now())
                ), new PageInfo(null, false)),
                new Items(existingItems)
        );
    }

    static Item existingItem(String url) {
        return new Item("item-1", "ISSUE",
                new Content("content-1", "Existing", 0, url, Instant.now(), Instant.now()),
                null);
    }

    @BeforeEach
    void reset() {
        detectionService.runningDetections.clear();
    }

    @Test
    void issueFromSharedRepoIsAssignedToOnlyOneWG() throws Exception {
        // Two WGs sharing quarkusio/quarkus
        var observabilityWG = wg("WG - Observability.Next",
                "Improve observability: OpenTelemetry, metrics, tracing, logging improvements");
        var dataWG = wg("WG - Quarkus Data",
                "Improve data layer: Hibernate ORM, Panache, reactive datasources");

        Mockito.when(workingGroupService.fetchAllWorkingGroups())
                .thenReturn(List.of(observabilityWG, dataWG));

        // Issues from the shared repo
        var otelIssue = issue(101, "Add OTel tracing for gRPC",
                "Add OpenTelemetry distributed tracing support for gRPC services");
        var panacheIssue = issue(102, "Panache finder methods regression",
                "Panache entity finder methods throw ClassCastException with Hibernate 6.4");
        var generalBug = issue(103, "Fix NPE in config parser",
                "NullPointerException when config property is missing");

        var repoData = new GitHubRepositoryService.RepositoryData("quarkus", "quarkusio");
        repoData.getIssuesAndPRs().addAll(List.of(otelIssue, panacheIssue, generalBug));
        Mockito.when(repositoryService.get("quarkusio/quarkus")).thenReturn(repoData);

        Mockito.when(workingGroupService.hasItem(any(WorkingGroupBoard.class), any(IssueOrPR.class)))
                .thenReturn(false);

        // AI classifies each issue to exactly one WG (or none)
        String aiResponse = """
                {"results": {
                    "quarkusio/quarkus#101": "WG - Observability.Next",
                    "quarkusio/quarkus#102": "WG - Quarkus Data",
                    "quarkusio/quarkus#103": ""
                }}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        DetectionRun run = detectionService.createDetectionRun(14);
        detectionService.executeDetection(run);

        assertThat(run.status).isEqualTo("COMPLETED");

        // Key assertion: each issue appears in exactly one WG
        assertThat(run.results.get("WG - Observability.Next"))
                .containsExactly(otelIssue)
                .doesNotContain(panacheIssue, generalBug);
        assertThat(run.results.get("WG - Quarkus Data"))
                .containsExactly(panacheIssue)
                .doesNotContain(otelIssue, generalBug);

        // General bug should not appear in any WG
        assertThat(run.results.values().stream().flatMap(List::stream).toList())
                .doesNotContain(generalBug);
    }

    @Test
    void issueAlreadyInWGProjectIsFilteredOut() throws Exception {
        var wg1 = wg("WG - Observability.Next",
                "Observability improvements",
                List.of(existingItem("https://github.com/quarkusio/quarkus/issues/200")));

        Mockito.when(workingGroupService.fetchAllWorkingGroups()).thenReturn(List.of(wg1));

        var existingIssue = issue(200, "Already tracked OTel issue", "This is already in the WG");
        var newIssue = issue(201, "New OTel metrics feature", "Add new OTel metrics");

        var repoData = new GitHubRepositoryService.RepositoryData("quarkus", "quarkusio");
        repoData.getIssuesAndPRs().addAll(List.of(existingIssue, newIssue));
        Mockito.when(repositoryService.get("quarkusio/quarkus")).thenReturn(repoData);

        Mockito.when(workingGroupService.hasItem(eq(wg1), eq(existingIssue))).thenReturn(true);
        Mockito.when(workingGroupService.hasItem(eq(wg1), eq(newIssue))).thenReturn(false);

        String aiResponse = """
                {"results": {"quarkusio/quarkus#201": "WG - Observability.Next"}}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        DetectionRun run = detectionService.createDetectionRun(14);
        detectionService.executeDetection(run);

        assertThat(run.status).isEqualTo("COMPLETED");
        assertThat(run.results.get("WG - Observability.Next"))
                .containsExactly(newIssue)
                .doesNotContain(existingIssue);
    }

    @Test
    void issueWithExcludedLabelIsFilteredOut() throws Exception {
        var wg1 = wg("WG - Observability.Next", "Observability improvements");

        Mockito.when(workingGroupService.fetchAllWorkingGroups()).thenReturn(List.of(wg1));

        var validIssue = issue(301, "OTel tracing feature", "Add tracing");
        var invalidIssue = issueWithLabels(302, "Invalid OTel issue", "desc", List.of("triage/invalid"));
        var duplicateIssue = issueWithLabels(303, "Duplicate tracing", "desc", List.of("duplicate"));

        var repoData = new GitHubRepositoryService.RepositoryData("quarkus", "quarkusio");
        repoData.getIssuesAndPRs().addAll(List.of(validIssue, invalidIssue, duplicateIssue));
        Mockito.when(repositoryService.get("quarkusio/quarkus")).thenReturn(repoData);

        Mockito.when(workingGroupService.hasItem(any(WorkingGroupBoard.class), any(IssueOrPR.class)))
                .thenReturn(false);

        String aiResponse = """
                {"results": {"quarkusio/quarkus#301": "WG - Observability.Next"}}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        DetectionRun run = detectionService.createDetectionRun(14);
        detectionService.executeDetection(run);

        assertThat(run.status).isEqualTo("COMPLETED");
        assertThat(run.results.get("WG - Observability.Next"))
                .containsExactly(validIssue);
    }

    @Test
    void multipleWGsWithDifferentReposDoNotOverlap() throws Exception {
        var observabilityWG = wg("WG - Observability.Next", "Observability improvements");
        var langchain4jWG = wg("WG - Agentic Foundation and LangChain4j Next",
                "AI/LLM integration with LangChain4j for Quarkus");

        Mockito.when(workingGroupService.fetchAllWorkingGroups())
                .thenReturn(List.of(observabilityWG, langchain4jWG));

        var quarkusIssue = issue("quarkusio", "quarkus", 401,
                "Add OTel baggage propagation", "OpenTelemetry baggage support");
        var langchainIssue = issue("quarkiverse", "quarkus-langchain4j", 402,
                "Add tool calling support", "Implement function calling for AI agents");

        var quarkusRepo = new GitHubRepositoryService.RepositoryData("quarkus", "quarkusio");
        quarkusRepo.getIssuesAndPRs().add(quarkusIssue);
        Mockito.when(repositoryService.get("quarkusio/quarkus")).thenReturn(quarkusRepo);

        var langchainRepo = new GitHubRepositoryService.RepositoryData("quarkus-langchain4j", "quarkiverse");
        langchainRepo.getIssuesAndPRs().add(langchainIssue);
        Mockito.when(repositoryService.get("quarkiverse/quarkus-langchain4j")).thenReturn(langchainRepo);

        Mockito.when(workingGroupService.hasItem(any(WorkingGroupBoard.class), any(IssueOrPR.class)))
                .thenReturn(false);

        String aiResponse = """
                {"results": {
                    "quarkusio/quarkus#401": "WG - Observability.Next",
                    "quarkiverse/quarkus-langchain4j#402": "WG - Agentic Foundation and LangChain4j Next"
                }}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        DetectionRun run = detectionService.createDetectionRun(14);
        detectionService.executeDetection(run);

        assertThat(run.status).isEqualTo("COMPLETED");
        assertThat(run.results.get("WG - Observability.Next")).containsExactly(quarkusIssue);
        assertThat(run.results.get("WG - Agentic Foundation and LangChain4j Next"))
                .containsExactly(langchainIssue);
    }

    @Test
    void wgWithoutRepositoryMappingIsReported() throws Exception {
        var mappedWG = wg("WG - Observability.Next", "Observability improvements");
        var unmappedWG = wg("WG - Brand New WG", "A brand new WG with no repo mapping");

        Mockito.when(workingGroupService.fetchAllWorkingGroups())
                .thenReturn(List.of(mappedWG, unmappedWG));

        var repoData = new GitHubRepositoryService.RepositoryData("quarkus", "quarkusio");
        Mockito.when(repositoryService.get("quarkusio/quarkus")).thenReturn(repoData);

        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString()))
                .thenReturn("""
                        {"results": {}}""");

        DetectionRun run = detectionService.createDetectionRun(14);
        detectionService.executeDetection(run);

        assertThat(run.status).isEqualTo("COMPLETED");
        assertThat(run.workingGroupsWithoutRepositories).contains("WG - Brand New WG");
    }

    @Test
    void completedAndPausedWGsAreSkipped() throws Exception {
        // Completed WG (has COMPLETE status update)
        var completedWG = new WorkingGroupBoard(
                "project-completed", "WG - Completed Feature", "Completed proposal",
                "https://github.com/orgs/quarkusio/projects/2",
                Instant.now().minus(60, ChronoUnit.DAYS), Instant.now(),
                new StatusUpdates(List.of(
                        new StatusUpdate("su-c", "Done!", "", "COMPLETE", Instant.now())
                ), new PageInfo(null, false)),
                new Items(List.of())
        );

        // Active WG
        var activeWG = wg("WG - Observability.Next", "Observability improvements");

        Mockito.when(workingGroupService.fetchAllWorkingGroups())
                .thenReturn(List.of(completedWG, activeWG));

        var repoData = new GitHubRepositoryService.RepositoryData("quarkus", "quarkusio");
        repoData.getIssuesAndPRs().add(issue(501, "OTel issue", "desc"));
        Mockito.when(repositoryService.get("quarkusio/quarkus")).thenReturn(repoData);

        Mockito.when(workingGroupService.hasItem(any(WorkingGroupBoard.class), any(IssueOrPR.class)))
                .thenReturn(false);

        String aiResponse = """
                {"results": {"quarkusio/quarkus#501": "WG - Observability.Next"}}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        DetectionRun run = detectionService.createDetectionRun(14);
        detectionService.executeDetection(run);

        assertThat(run.status).isEqualTo("COMPLETED");
        // Completed WG should not appear in results
        assertThat(run.results).doesNotContainKey("WG - Completed Feature");
        assertThat(run.results.get("WG - Observability.Next")).hasSize(1);
    }

    @Test
    void manyWGsSharingOneRepoAreClassifiedCorrectly() throws Exception {
        // Simulate the real scenario: many WGs sharing quarkusio/quarkus
        var observabilityWG = wg("WG - Observability.Next",
                "OpenTelemetry, metrics, tracing, logging, Micrometer improvements");
        var dataWG = wg("WG - Quarkus Data",
                "Hibernate ORM, Panache, reactive datasources, data layer improvements");
        var shutdownWG = wg("WG - Graceful Shutdown",
                "Graceful shutdown: drain connections, complete in-flight requests, lifecycle hooks");
        var devServicesWG = wg("WG - Dev Services Lifecycle",
                "Dev Services: testcontainers, lifecycle management, shared services");
        var java25WG = wg("WG - Java 25 support",
                "Java 25 compatibility: virtual threads improvements, new language features");

        Mockito.when(workingGroupService.fetchAllWorkingGroups())
                .thenReturn(List.of(observabilityWG, dataWG, shutdownWG, devServicesWG, java25WG));

        // All issues from the same repo
        var otelIssue = issue(601, "Add OTel log appender",
                "Create an OpenTelemetry log appender that integrates with Quarkus logging");
        var panacheIssue = issue(602, "Panache sorting broken with joins",
                "PanacheQuery.sort() produces invalid SQL when used with entity joins");
        var shutdownIssue = issue(603, "Drain HTTP connections on shutdown",
                "Implement connection draining during graceful shutdown for HTTP server");
        var devServiceIssue = issue(604, "PostgreSQL Dev Service fails with podman",
                "Dev Services PostgreSQL container fails to start with Podman as container runtime");
        var generalBug = issue(605, "Bump netty to 4.1.108",
                "Update netty dependency from 4.1.107 to 4.1.108");
        var ambiguousIssue = issue(606, "Improve startup performance",
                "General startup time improvements for Quarkus applications");

        var repoData = new GitHubRepositoryService.RepositoryData("quarkus", "quarkusio");
        repoData.getIssuesAndPRs().addAll(List.of(
                otelIssue, panacheIssue, shutdownIssue, devServiceIssue, generalBug, ambiguousIssue));
        Mockito.when(repositoryService.get("quarkusio/quarkus")).thenReturn(repoData);

        Mockito.when(workingGroupService.hasItem(any(WorkingGroupBoard.class), any(IssueOrPR.class)))
                .thenReturn(false);

        // AI correctly assigns each issue to exactly one WG
        String aiResponse = """
                {"results": {
                    "quarkusio/quarkus#601": "WG - Observability.Next",
                    "quarkusio/quarkus#602": "WG - Quarkus Data",
                    "quarkusio/quarkus#603": "WG - Graceful Shutdown",
                    "quarkusio/quarkus#604": "WG - Dev Services Lifecycle",
                    "quarkusio/quarkus#605": "",
                    "quarkusio/quarkus#606": ""
                }}""";
        Mockito.when(issueClassifier.classifyBatch(anyString(), anyString())).thenReturn(aiResponse);

        DetectionRun run = detectionService.createDetectionRun(14);
        detectionService.executeDetection(run);

        assertThat(run.status).isEqualTo("COMPLETED");

        assertThat(run.results.get("WG - Observability.Next")).containsExactly(otelIssue);
        assertThat(run.results.get("WG - Quarkus Data")).containsExactly(panacheIssue);
        assertThat(run.results.get("WG - Graceful Shutdown")).containsExactly(shutdownIssue);
        assertThat(run.results.get("WG - Dev Services Lifecycle")).containsExactly(devServiceIssue);
        assertThat(run.results.getOrDefault("WG - Java 25 support", List.of())).isEmpty();

        // Verify no issue appears in multiple WGs
        List<IssueOrPR> allAssigned = run.results.values().stream()
                .flatMap(List::stream)
                .toList();
        assertThat(allAssigned)
                .doesNotHaveDuplicates()
                .doesNotContain(generalBug, ambiguousIssue);
    }
}
