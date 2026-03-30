package io.quarkus.orbit.wg.update;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import jakarta.inject.Inject;
import io.quarkus.orbit.wg.WorkingGroupView;
import io.quarkus.orbit.wg.WorkingGroups;
import io.quarkus.orbit.wg.graphql.Item;
import io.quarkus.orbit.wg.graphql.WorkingGroupBoard;
import picocli.CommandLine;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

@CommandLine.Command(name = "generate-status-update", mixinStandardHelpOptions = true, version = "0.1",
        description = "Automatically compute status updates for working groups")
public class WorkingGroupStatusUpdater implements Callable<Integer> {

    @CommandLine.Option(names = "--from",
            description = "The starting date for the period, 30 days by default. The format must be yyyy-MM-dd.")
    private String from;

    @CommandLine.Option(names = "--to",
            description = "The ending date for the period, today by default. The format must be yyyy-MM-dd.")
    private String to;

    @Inject
    WorkingGroups workingGroups;

    @Inject
    UpdateGenerator generator;

    @GraphQLClient("github")
    DynamicGraphQLClient githubClient;

    public Integer call() throws Exception {
        if (from == null) {
            Instant twoWeeks = Instant.now().minus(30, ChronoUnit.DAYS);
            from = new SimpleDateFormat("yyyy-MM-dd").format(Date.from(twoWeeks));
        }
        if (to == null) {
            to = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        }

        Log.infof("Looking at working groups status updates from %s to %s", from, to);

        try {
            List<WorkingGroupBoard> groups = workingGroups.fetchAllWorkingGroups();

            WorkingGroupView view = new WorkingGroupView(groups,
                    new SimpleDateFormat("yyyy-MM-dd").parse(from).toInstant().minus(1, ChronoUnit.DAYS),
                    new SimpleDateFormat("yyyy-MM-dd").parse(to).toInstant().plus(1, ChronoUnit.DAYS));

            for (WorkingGroupBoard board : view) {
                if (board.isCompleted() || board.isLTS()  || board.isPaused()) {
                    continue;
                }
                var lastUpdate = view.getLastUpdate(board, true);
                if (lastUpdate == null
                        && (!view.getCompletedItems(board).isEmpty() || !view.getNewItems(board).isEmpty())) {
                    var update = generateUpdate(board, view);
                    Log.infof("Updating working group %s with update: %s", board.name(), update);
                    addStatusWorkingGroup(board, update);
                }
            }
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    private void addStatusWorkingGroup(WorkingGroupBoard board, String update) {
        try {
            Map<String, Object> variables = Map.of(
                    "id", board.id(), "update", update + "\n\n" +
                            "(This status update was automatically generated using AI.)");
            var r = githubClient.executeSync("""
                    mutation($id:ID!, $update:String!){
                       createProjectV2StatusUpdate(
                         input: {projectId: $id, body: $update, status: ON_TRACK}
                       )
                       {
                         statusUpdate {
                           id
                           body
                           status
                         }
                       }
                    }
                    """, variables);
            if (r.getErrors() == null || r.getErrors().isEmpty()) {
                Log.infof("\uD83D\uDFE2 Board %s updated", board.name());
            } else {
                Log.errorf("\uD83D\uDD34 Board %s failed to update: %s", board.name(), r.getErrors());
            }

        } catch (ExecutionException | InterruptedException e) {
            Log.errorf(e, "\uD83D\uDD34 Error updating working group %s", board.name(), e);
        }
    }


    private String generateUpdate(WorkingGroupBoard board, WorkingGroupView view) {
        List<String> completed = view.getCompletedItems(board)
                .stream().map(Item::title).toList();
        List<String> opened = view.getNewItems(board)
                .stream().map(Item::title).toList();

        Log.infof("Generating status update for %s", board.name());
        return generator.generateStatusUpdate(board.name(), board.shortDescription(), completed, opened);
    }
}
