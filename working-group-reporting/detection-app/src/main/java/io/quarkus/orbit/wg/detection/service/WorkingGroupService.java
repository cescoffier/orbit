package io.quarkus.orbit.wg.detection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonArray;
import jakarta.json.JsonValue;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;
import io.quarkus.orbit.wg.detection.model.Items;
import io.quarkus.orbit.wg.detection.model.WorkingGroupBoard;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Singleton
public class WorkingGroupService {

    @ConfigProperty(name = "working-groups.organizations", defaultValue = "quarkusio,quarkiverse")
    List<String> organizations;

    @ConfigProperty(name = "working-groups.project-prefix", defaultValue = "WG -")
    String prefix;

    @GraphQLClient("github")
    DynamicGraphQLClient githubClient;

    @Inject
    ObjectMapper mapper;

    List<WorkingGroupBoard> workingGroups;

    public List<WorkingGroupBoard> fetchAllWorkingGroups() throws ExecutionException, InterruptedException, JsonProcessingException {
        Log.infof("Looking for working group projects in the following organizations: %s", organizations);
        Log.infof("Working group projects should start with the following prefix: `%s`", prefix);

        List<WorkingGroupBoard> boards = new ArrayList<>();
        for (String org : organizations) {
            boards.addAll(getAllWorkingGroupsForOrganization(org));
        }

        Log.infof("\uD83D\uDCA1 Found %d working group projects, %d active including %d LTS", boards.size(),
                boards.stream().filter(w -> !w.isCompleted()).count(),
                boards.stream().filter(WorkingGroupBoard::isLTS).count());
        boards.sort(Comparator.comparing(WorkingGroupBoard::updatedAt).reversed());

        workingGroups = boards;
        return boards;
    }


    /**
     * Fetch working group metadata (without items) for an organization.
     * This is Phase 1 of the 2-phase fetch approach.
     */
    List<WorkingGroupBoard> fetchWorkingGroupMetadata(String org) throws ExecutionException, InterruptedException, JsonProcessingException {
        List<WorkingGroupBoard> boards = new ArrayList<>();
        Map<String, Object> variables = Map.of("organization", org, "first", 100);
        Response response = githubClient.executeSync("""
                 query($organization: String!, $first: Int!){
                   organization(login: $organization){
                     projectsV2(first: $first) {
                       nodes {
                         id
                         title
                         readme
                         url
                         createdAt
                         updatedAt
                         statusUpdates(first: $first) {
                           nodes {
                             id
                             body
                             bodyHTML
                             status
                             createdAt
                           }
                           pageInfo {
                             endCursor
                             hasNextPage
                           }
                         }
                       }
                       pageInfo {
                         endCursor
                         hasNextPage
                       }
                     }
                   }
                 }
                """, variables);

        if (response.hasError()) {
            throw new RuntimeException("Unable to fetch working group metadata for organization " + org + " - " + response.getErrors());
        }

        JsonArray array = response.getData().getJsonObject("organization").getJsonObject("projectsV2")
                .getJsonArray("nodes");

        for (JsonValue value : array) {
            var json = value.asJsonObject();
            var t = json.getString("title");
            if (t.startsWith(prefix)) {
                // Create a temporary board with empty items
                String jsonWithEmptyItems = json.toString().replace("}", ", \"items\": {\"nodes\": []}}");
                WorkingGroupBoard board = mapper.readValue(jsonWithEmptyItems, WorkingGroupBoard.class);
                boards.add(board);
            }
        }
        return boards;
    }

    /**
     * Fetch items for a specific working group project by ID.
     * This is Phase 2 of the 2-phase fetch approach.
     */
    Items fetchItemsForWorkingGroup(String projectId) throws ExecutionException, InterruptedException, JsonProcessingException {
        Map<String, Object> variables = Map.of("projectId", projectId);
        Response response = githubClient.executeSync("""
                 query($projectId: ID!){
                   node(id: $projectId) {
                     ... on ProjectV2 {
                       items(first: 100) {
                         nodes {
                           id
                           type
                           content {
                             ... on Issue {
                               id
                               title
                               number
                               url
                               updatedAt
                               createdAt
                             }
                             ... on PullRequest {
                               id
                               title
                               number
                               url
                               updatedAt
                               createdAt
                             }
                             ... on DraftIssue {
                               id
                               title
                               updatedAt
                               createdAt
                             }
                           }
                         }
                       }
                     }
                   }
                 }
                """, variables);

        if (response.hasError()) {
            throw new RuntimeException("Unable to fetch items for working group " + projectId + " - " + response.getErrors());
        }

        var itemsJson = response.getData().getJsonObject("node").getJsonObject("items");
        return mapper.readValue(itemsJson.toString(), Items.class);
    }

    List<WorkingGroupBoard> getAllWorkingGroupsForOrganization(String org) throws ExecutionException, InterruptedException, JsonProcessingException {
        // Phase 1: Fetch metadata only
        Log.infof("Phase 1: Fetching working group metadata for organization %s", org);
        List<WorkingGroupBoard> metadataBoards = fetchWorkingGroupMetadata(org);
        Log.infof("Found %d working group projects in %s", metadataBoards.size(), org);

        // Filter out completed, paused, and LTS working groups
        List<WorkingGroupBoard> activeBoards = metadataBoards.stream()
                .filter(WorkingGroupBoard::shouldProcess)
                .toList();

        int skipped = metadataBoards.size() - activeBoards.size();
        Log.infof("Skipping %d completed/paused/LTS working groups, will fetch items for %d active working groups",
                skipped, activeBoards.size());

        // Phase 2: Fetch items for active working groups
        List<WorkingGroupBoard> completeBoards = new ArrayList<>();
        for (WorkingGroupBoard board : activeBoards) {
            try {
                Log.infof("Phase 2: Fetching items for working group '%s'", board.name());
                Items items = fetchItemsForWorkingGroup(board.id());
                // Create a new board with the fetched items
                WorkingGroupBoard completeBoard = new WorkingGroupBoard(
                        board.id(),
                        board.name(),
                        board.proposal(),
                        board.url(),
                        board.createdAt(),
                        board.updatedAt(),
                        board.statusUpdates(),
                        items
                );
                completeBoards.add(completeBoard);
            } catch (Exception e) {
                Log.errorf(e, "Failed to fetch items for working group '%s', skipping", board.name());
            }
        }

        // Also add back the filtered out boards (completed/paused/LTS) with empty items
        metadataBoards.stream()
                .filter(board -> !board.shouldProcess())
                .forEach(completeBoards::add);

        return completeBoards;
    }

    public boolean hasItem(String wgName, IssueOrPR issueOrPR) {
        if (workingGroups == null) {
            try {
                fetchAllWorkingGroups();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        for (WorkingGroupBoard board : workingGroups) {
            if (board.name().equals(wgName)) {
                return board.getItems().stream()
                        .anyMatch(item -> item.url().equalsIgnoreCase(issueOrPR.url()));
            }
        }
        return false;
    }

    public boolean hasItem(WorkingGroupBoard board, IssueOrPR issueOrPR) {
        if (board.getItems() == null || board.getItems().isEmpty()) {
            Log.warnf("Working group %s has no items loaded", board.name());
            return false;
        }
        return board.getItems().stream()
                .anyMatch(item -> item.url() != null && item.url().equalsIgnoreCase(issueOrPR.url()));

    }

    public List<WorkingGroupBoard> get() {
        if (workingGroups == null) {
            try {
                fetchAllWorkingGroups();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return workingGroups;
    }
}
