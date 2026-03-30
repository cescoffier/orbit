package io.quarkus.orbit.wg.detection.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;
import io.quarkus.orbit.wg.detection.model.WorkingGroupBoard;

import java.util.List;
import java.util.Map;

/**
 * Service to apply approved associations between Issues/PRs and Working Groups.
 *
 * This service adds approved issues and PRs to their respective GitHub Project boards
 * with the appropriate status:
 * - Open issues → Todo
 * - Open PRs → In Progress
 * - Closed issues and merged PRs → Done
 */
@ApplicationScoped
public class AssociationService {

    @Inject
    ProjectAssociator projectAssociator;

    @Inject
    WorkingGroupService workingGroups;

    /**
     * Apply approved associations to GitHub Projects.
     *
     * @param runId The detection run ID
     * @param approvedByWorkingGroup Map of working group name to list of approved issues/PRs
     * @return Number of successfully applied associations
     */
    public int applyApprovedAssociations(String runId, Map<String, List<IssueOrPR>> approvedByWorkingGroup) {
        Log.infof("Applying approved associations for run %s - %d working group(s)",
                runId, approvedByWorkingGroup.size());

        int successCount = 0;
        int failureCount = 0;

        // Iterate over each working group
        for (Map.Entry<String, List<IssueOrPR>> entry : approvedByWorkingGroup.entrySet()) {
            String workingGroupName = entry.getKey();
            List<IssueOrPR> issuesToAssign = entry.getValue();

            Log.infof("Processing %d item(s) for working group: %s", issuesToAssign.size(), workingGroupName);

            // Find the working group to get its project ID
            WorkingGroupBoard wg = findWorkingGroup(workingGroupName);
            if (wg == null) {
                Log.errorf("Working group not found: %s - skipping %d items", workingGroupName, issuesToAssign.size());
                failureCount += issuesToAssign.size();
                continue;
            }

            // The working group board already has the GitHub Project ID
            String projectId = wg.id();
            if (projectId == null || projectId.isEmpty()) {
                Log.errorf("Working group %s has no project ID - skipping %d items", workingGroupName, issuesToAssign.size());
                failureCount += issuesToAssign.size();
                continue;
            }

            Log.infof("Using project ID %s for working group: %s", projectId, workingGroupName);

            // Add each issue/PR to the GitHub Project
            for (IssueOrPR issue : issuesToAssign) {
                try {
                    addToProject(issue, projectId, workingGroupName);
                    successCount++;
                } catch (Exception e) {
                    Log.errorf(e, "Failed to add %s #%d to project for %s",
                            issue.type(), issue.number(), workingGroupName);
                    failureCount++;
                }
            }
        }

        Log.infof("Association complete: %d succeeded, %d failed", successCount, failureCount);
        return successCount;
    }

    /**
     * Find a working group by name.
     */
    private WorkingGroupBoard findWorkingGroup(String name) {
        return workingGroups.get().stream()
                .filter(wg -> wg.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Add an issue or PR to a GitHub Project with the appropriate status.
     *
     * Status assignment:
     * - Open issue → Todo
     * - Open PR → In Progress
     * - Closed issue or merged PR → Done
     */
    private void addToProject(IssueOrPR issue, String projectId, String workingGroupName) throws Exception {
        Log.infof("Adding %s #%d to project for working group: %s (status will be: %s)",
                issue.type(), issue.number(), workingGroupName, issue.determineProjectStatus());

        boolean success = projectAssociator.addToProject(issue, projectId, workingGroupName);

        if (success) {
            Log.infof("✅ Successfully added %s #%d to %s project",
                    issue.type(), issue.number(), workingGroupName);
        } else {
            throw new Exception("ProjectAssociator returned false");
        }
    }
}
