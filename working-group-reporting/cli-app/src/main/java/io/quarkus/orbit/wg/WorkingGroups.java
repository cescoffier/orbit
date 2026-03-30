package io.quarkus.orbit.wg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import io.quarkus.orbit.wg.graphql.Items;
import io.quarkus.orbit.wg.graphql.WorkingGroupBoard;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Singleton
public class WorkingGroups {

    @ConfigProperty(name = "working-groups.organizations", defaultValue = "quarkusio,quarkiverse")
    List<String> organizations;

    @ConfigProperty(name = "working-groups.project-prefix", defaultValue = "WG -")
    String prefix;

    @GraphQLClient("github")
    DynamicGraphQLClient githubClient;

    @Inject
    ObjectMapper mapper;

    public List<WorkingGroupBoard> fetchAllWorkingGroups() throws ExecutionException, InterruptedException, JsonProcessingException {
        Log.infof("Looking for working group projects in the following organizations: %s", organizations);
        Log.infof("Working group projects should start with the following prefix: `%s`", prefix);

        List<WorkingGroupBoard> boards = new ArrayList<>();
        for (String org : organizations) {
            boards.addAll(getAllWorkingGroupsForOrganization(org));
        }

        Log.infof("\uD83D\uDCA1 Found %d working group projects, %d active including %d LTS", boards.size(),
                boards.stream().filter(w -> ! w.isCompleted()).count(),
                boards.stream().filter(WorkingGroupBoard::isLTS).count());
        boards.sort(Comparator.comparing(WorkingGroupBoard::updatedAt).reversed());

        return boards;
    }


    private Items fetchItemsForProject(String projectId) throws ExecutionException, InterruptedException, JsonProcessingException {
        Map<String, Object> variables = Map.of("projectId", projectId, "first", 100);

        Response response = githubClient.executeSync("""
            query($projectId: ID!, $first: Int!) {
              node(id: $projectId) {
                ... on ProjectV2 {
                  items(first: $first) {
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
                      fieldValueByName(name: "Status") {
                        ... on ProjectV2ItemFieldSingleSelectValue {
                          status: name
                          updatedAt
                          createdAt
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
            }
        """, variables);

        if (response.hasError()) {
            Log.errorf("Unable to fetch items for project %s - %s", projectId, response.getErrors());
            return new Items(new ArrayList<>());
        }

        JsonObject node = response.getData().getJsonObject("node");
        if (node == null || node.isNull("items")) {
            return new Items(new ArrayList<>());
        }

        JsonObject itemsJson = node.getJsonObject("items");
        return mapper.readValue(itemsJson.toString(), Items.class);
    }

    private WorkingGroupBoard mergeItemsIntoBoard(WorkingGroupBoard board, Items items) {
        return new WorkingGroupBoard(
            board.id(),
            board.name(),
            board.shortDescription(),
            board.readme(),
            board.url(),
            board.createdAt(),
            board.updatedAt(),
            board.statusUpdates(),
            items
        );
    }

    public List<WorkingGroupBoard> getAllWorkingGroupsForOrganization(String org) throws ExecutionException, InterruptedException, JsonProcessingException {
        Log.infof("Fetching working group projects for organization: %s", org);

        List<WorkingGroupBoard> boards = new ArrayList<>();
        Map<String, Object> variables = Map.of("organization", org, "first", 100);

        // Phase 1: Fetch projects WITHOUT items
        Response response = githubClient.executeSync("""
                 query($organization: String!, $first: Int!){
                                         organization(login: $organization){
                                           projectsV2(first: $first) {
                                            nodes {
                                             id
                                             title
                                             shortDescription
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
            throw new RuntimeException("Unable to fetch the working groups for organization " + org + " - " + response.getErrors());
        }

        JsonArray array = response.getData().getJsonObject("organization").getJsonObject("projectsV2")
                .getJsonArray("nodes");

        // Filter working group projects
        List<WorkingGroupBoard> projectsToEnrich = new ArrayList<>();
        for (JsonValue value : array) {
            var json = value.asJsonObject();
            var t = json.getString("title");
            if (t.startsWith(prefix)) {
                // Add empty items field for JSON deserialization
                String jsonString = json.toString();
                // Insert empty items array before the closing brace
                jsonString = jsonString.substring(0, jsonString.length() - 1) + ",\"items\":{\"nodes\":[]}}";
                WorkingGroupBoard board = mapper.readValue(jsonString, WorkingGroupBoard.class);
                projectsToEnrich.add(board);
            }
        }

        Log.infof("Found %d working group projects in %s, fetching items...", projectsToEnrich.size(), org);

        // Phase 2: Fetch items for each project sequentially
        for (WorkingGroupBoard board : projectsToEnrich) {
            try {
                Items items = fetchItemsForProject(board.id());
                WorkingGroupBoard enrichedBoard = mergeItemsIntoBoard(board, items);
                boards.add(enrichedBoard);
                Log.debugf("Fetched %d items for project: %s", items.nodes().size(), board.name());
            } catch (Exception e) {
                Log.errorf(e, "Failed to fetch items for project %s, using empty items", board.name());
                boards.add(mergeItemsIntoBoard(board, new Items(new ArrayList<>())));
            }
        }

        return boards;
    }

}
