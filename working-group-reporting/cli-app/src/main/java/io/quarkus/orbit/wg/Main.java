package io.quarkus.orbit.wg;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.logging.Log;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.orbit.wg.graphql.StatusUpdate;
import io.quarkus.orbit.wg.graphql.WorkingGroupBoard;
import io.quarkus.orbit.wg.poc.PointOfContactManager;
import io.quarkus.orbit.wg.update.WorkingGroupStatusUpdater;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true,
        subcommands = {ReportCommand.class, PointOfContactCommand.class,
                ReminderEmailCommand.class, WorkingGroupStatusUpdater.class
        })
public class Main {
}

@CommandLine.Command(name = "report", mixinStandardHelpOptions = true, version = "0.1",
        description = "Generate the Quarkus Monthly Report")
class ReportCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--output",
            description = "The output file", defaultValue = "report.md")
    private File output;

    @CommandLine.Option(names = "--from",
            description = "The starting date for the period, 30 days by default. The format must be yyyy-MM-dd.")
    private String from;

    @CommandLine.Option(names = "--to",
            description = "The ending date for the period, today by default. The format must be yyyy-MM-dd.")
    private String to;

    @Inject
    WorkingGroups workingGroups;

    @Inject
    @Location("report.md")
    Template template;

    public Integer call() throws Exception {
        if (from == null) {
            Instant twoWeeks = Instant.now().minus(30, ChronoUnit.DAYS);
            from = new SimpleDateFormat("yyyy-MM-dd").format(Date.from(twoWeeks));
        }
        if (to == null) {
            to = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        }

        Log.infof("Generating the report in %s, from %s to %s", output, from, to);

        try {
            List<WorkingGroupBoard> groups = workingGroups.fetchAllWorkingGroups();
            WorkingGroupView view = new WorkingGroupView(groups,
                    new SimpleDateFormat("yyyy-MM-dd").parse(from).toInstant().minus(1, ChronoUnit.DAYS),
                    new SimpleDateFormat("yyyy-MM-dd").parse(to).toInstant().plus(1, ChronoUnit.DAYS));

            for (WorkingGroupBoard board : view) {
                if (board.isCompleted()  || board.isLTS()) {
                    continue;
                }
                boolean activity = view.hasActivity(board, true);
                if (!activity) {
                    Log.warnf("\uD83D\uDD34 %s => no activity (no status, no new or closed issues)", board.name());
                }
            }
            Files.writeString(output.toPath(), template.data("from", from, "to", to, "groups", view).render());
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }
}

@CommandLine.Command(name = "point-of-contact", mixinStandardHelpOptions = true, version = "0.1",
        description = "Check and manage the point of contact for the Quarkus Working Groups")
class PointOfContactCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--working-group",
            description = "The working group name to check")
    private String workingGroup;

    @CommandLine.Option(names = "--contact",
            description = "The contact to set")
    private String contact;


    @Inject
    PointOfContactManager manager;

    @Override
    @ActivateRequestContext
    @Transactional
    public Integer call() {
        if (workingGroup == null && contact == null) {
            // Display the point of contact of all working groups
            manager.printAllWorkingGroups();
            return 0;
        }
        if (workingGroup != null && contact == null) {
            // Display the point of contact of the given working group
            manager.printPointOfContact(workingGroup);
            return 0;
        }
        if (workingGroup == null) {
            // Invalid. Display an error message
            Log.errorf("\uD83D\uDD34 You must specify a working group when specifying a point of contact");
            return 1;
        }
        // Set the point of contact of the given working group
        manager.setPointOfContact(workingGroup, contact);
        return 0;

    }


}


@CommandLine.Command(name = "reminder", mixinStandardHelpOptions = true, version = "0.1",
        description = "Checks the working groups without updates and prepare the email to remind the point of contact.")
class ReminderEmailCommand implements Callable<Integer> {

    @Inject
    WorkingGroups workingGroups;

    @Inject
    PointOfContactManager manager;

    @CommandLine.Option(names = "--from",
            description = "The starting date for the period, 30 days by default. The format must be yyyy-MM-dd.")
    private String from;

    @CommandLine.Option(names = "--to",
            description = "The ending date for the period, today by default. The format must be yyyy-MM-dd.")
    private String to;

    @Location("email.txt")
    Template email;

    @Override
    @ActivateRequestContext
    public Integer call() throws ExecutionException, InterruptedException, JsonProcessingException, ParseException {
        if (from == null) {
            Instant twoWeeks = Instant.now().minus(30, ChronoUnit.DAYS);
            from = new SimpleDateFormat("yyyy-MM-dd").format(Date.from(twoWeeks));
        }
        if (to == null) {
            to = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        }

        var list = workingGroups.fetchAllWorkingGroups();
        WorkingGroupView view = new WorkingGroupView(list,
                new SimpleDateFormat("yyyy-MM-dd").parse(from).toInstant().minus(1, ChronoUnit.DAYS),
                new SimpleDateFormat("yyyy-MM-dd").parse(to).toInstant().plus(1, ChronoUnit.DAYS));

        // Find the working group without updates
        for (WorkingGroupBoard group : list) {
            if (group.isCompleted()) {
                continue;
            }
            var contact = manager.getPointOfContact(group.name());
            if (contact == null) {
                Log.warn("\uD83D\uDD34 No point of contact for " + group.name() + ", please update the database.");
            }
            if (!group.isCompleted()) {
                StatusUpdate update = view.getLastUpdate(group, true);
                if ((update == null || isLastUpdateToOld(update)) && contact != null) {
                    // Compute an email to the point of contact
                    System.out.println("---------------");
                    System.out.println("to: " + contact);
                    System.out.println("subject: Reminder - Update the status of the Quarkus Working Group " + group.name());
                    System.out.println(email.data("name", group.name(), "url", group.url()).render());
                    System.out.println("---------------");
                }

            }
        }

        return 0;

    }

    private boolean isLastUpdateToOld(StatusUpdate update) {
        Instant now = Instant.now();
        return ChronoUnit.DAYS.between(update.createdAt(), now) > 21;
    }


}
