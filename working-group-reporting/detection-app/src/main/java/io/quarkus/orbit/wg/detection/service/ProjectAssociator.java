package io.quarkus.orbit.wg.detection.service;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import jakarta.inject.Singleton;
import jakarta.json.JsonObject;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;

import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Service to associate Issues and PRs with GitHub Projects.
 */
@Singleton
public class ProjectAssociator {

    @GraphQLClient("github")
    DynamicGraphQLClient githubClient;

    /**
     * Add an item to a GitHub Project V2 and set its status.
     *
     * @param item        The issue or PR to add
     * @param projectId   The GitHub Project V2 ID
     * @param workingGroupName The name of the working group
     * @return true if successful, false otherwise
     */
    public boolean addToProject(IssueOrPR item, String projectId, String workingGroupName)
            throws ExecutionException, InterruptedException {

        // Step 1: Add the item to the project
        String addMutation = """
            mutation($projectId: ID!, $contentId: ID!) {
              addProjectV2ItemById(input: {projectId: $projectId, contentId: $contentId}) {
                item {
                  id
                }
              }
            }
            """;

        Map<String, Object> addVariables = Map.of(
            "projectId", projectId,
            "contentId", item.id() // GitHub node ID
        );

        Response addResponse = githubClient.executeSync(addMutation, addVariables);

        if (addResponse.hasError()) {
            Log.errorf("Failed to add item %s to project: %s", item.url(), addResponse.getErrors());
            return false;
        }

        String projectItemId = addResponse.getData()
                .getJsonObject("addProjectV2ItemById")
                .getJsonObject("item")
                .getString("id");

        Log.infof("✅ Added %s to project (item ID: %s)", item.url(), projectItemId);

        // Step 2: Get the Status field ID
        String statusFieldId = getStatusFieldId(projectId);
        if (statusFieldId == null) {
            Log.warnf("Could not find Status field for project %s", projectId);
            // Still count this as success since the item was added
            postComment(item, workingGroupName);
            return true;
        }

        // Step 3: Get the Status field option ID for the desired status
        String desiredStatus = item.determineProjectStatus();
        String statusOptionId = getStatusOptionId(projectId, statusFieldId, desiredStatus);

        if (statusOptionId == null) {
            Log.warnf("Could not find status option '%s' for project %s", desiredStatus, projectId);
            // Still count this as success since the item was added
            postComment(item, workingGroupName);
            return true;
        }

        // Step 4: Update the Status field
        String updateMutation = """
            mutation($projectId: ID!, $itemId: ID!, $fieldId: ID!, $optionId: String!) {
              updateProjectV2ItemFieldValue(input: {
                projectId: $projectId,
                itemId: $itemId,
                fieldId: $fieldId,
                value: {singleSelectOptionId: $optionId}
              }) {
                projectV2Item {
                  id
                }
              }
            }
            """;

        Map<String, Object> updateVariables = Map.of(
            "projectId", projectId,
            "itemId", projectItemId,
            "fieldId", statusFieldId,
            "optionId", statusOptionId
        );

        Response updateResponse = githubClient.executeSync(updateMutation, updateVariables);

        if (updateResponse.hasError()) {
            Log.warnf("Failed to set status for item %s: %s", item.url(), updateResponse.getErrors());
        } else {
            Log.infof("✅ Set status to '%s' for %s", desiredStatus, item.url());
        }

        // Step 5: Post a comment
        postComment(item, workingGroupName);

        return true;
    }

    /**
     * Get the Status field ID for a project
     */
    private String getStatusFieldId(String projectId) throws ExecutionException, InterruptedException {
        String query = """
            query($projectId: ID!) {
              node(id: $projectId) {
                ... on ProjectV2 {
                  fields(first: 20) {
                    nodes {
                      ... on ProjectV2SingleSelectField {
                        id
                        name
                      }
                    }
                  }
                }
              }
            }
            """;

        Map<String, Object> variables = Map.of("projectId", projectId);
        Response response = githubClient.executeSync(query, variables);

        if (response.hasError()) {
            Log.warnf("Error fetching project fields: %s", response.getErrors());
            return null;
        }

        var fields = response.getData()
                .getJsonObject("node")
                .getJsonObject("fields")
                .getJsonArray("nodes");

        for (var field : fields) {
            JsonObject fieldObj = field.asJsonObject();
            // Check if the field has a "name" property (some field types don't)
            if (fieldObj.containsKey("name") && !fieldObj.isNull("name")) {
                String name = fieldObj.getString("name");
                if ("Status".equalsIgnoreCase(name)) {
                    return fieldObj.getString("id");
                }
            }
        }

        return null;
    }

    /**
     * Get the option ID for a specific status value
     */
    private String getStatusOptionId(String projectId, String fieldId, String statusName)
            throws ExecutionException, InterruptedException {

        String query = """
            query($projectId: ID!) {
              node(id: $projectId) {
                ... on ProjectV2 {
                  fields(first: 20) {
                    nodes {
                      ... on ProjectV2SingleSelectField {
                        id
                        name
                        options {
                          id
                          name
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

        Map<String, Object> variables = Map.of("projectId", projectId);
        Response response = githubClient.executeSync(query, variables);

        if (response.hasError()) {
            Log.warnf("Error fetching project field options: %s", response.getErrors());
            return null;
        }

        var fields = response.getData()
                .getJsonObject("node")
                .getJsonObject("fields")
                .getJsonArray("nodes");

        for (var field : fields) {
            JsonObject fieldObj = field.asJsonObject();
            // Check if this is the field we're looking for
            if (fieldObj.containsKey("id") && !fieldObj.isNull("id") && fieldObj.getString("id").equals(fieldId)) {
                // Found the right field, now look for the status option
                if (fieldObj.containsKey("options") && !fieldObj.isNull("options")) {
                    var options = fieldObj.getJsonArray("options");
                    for (var option : options) {
                        JsonObject optionObj = option.asJsonObject();
                        if (optionObj.containsKey("name") && !optionObj.isNull("name")) {
                            if (optionObj.getString("name").equalsIgnoreCase(statusName)) {
                                return optionObj.getString("id");
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Post a comment on the issue or PR
     */
    private void postComment(IssueOrPR item, String workingGroupName) {
        try {
            String mutation = """
                mutation($subjectId: ID!, $body: String!) {
                  addComment(input: {subjectId: $subjectId, body: $body}) {
                    commentEdge {
                      node {
                        id
                      }
                    }
                  }
                }
                """;

            String commentBody = String.format(
                "🤖 Automatically associated with the **%s** Working Group based on AI classification.",
                workingGroupName
            );

            Map<String, Object> variables = Map.of(
                "subjectId", item.id(),
                "body", commentBody
            );

            Response response = githubClient.executeSync(mutation, variables);

            if (response.hasError()) {
                Log.warnf("Failed to post comment on %s: %s", item.url(), response.getErrors());
            } else {
                Log.infof("✅ Posted comment on %s", item.url());
            }

        } catch (Exception e) {
            Log.warnf("Failed to post comment on %s: %s", item.url(), e.getMessage());
        }
    }
}
