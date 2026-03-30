package io.quarkus.orbit.wg;

import io.quarkus.logging.Log;
import io.quarkus.orbit.wg.graphql.Item;
import io.quarkus.orbit.wg.graphql.StatusUpdate;
import io.quarkus.orbit.wg.graphql.WorkingGroupBoard;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class WorkingGroupView extends AbstractList<WorkingGroupBoard> {
    private final List<WorkingGroupBoard> groups;
    private final Instant from;
    private final Instant to;

    public WorkingGroupView(List<WorkingGroupBoard> groups, Instant from, Instant to) {
        this.groups = groups;
        this.from = from;
        this.to = to;
    }

    @Override
    public WorkingGroupBoard get(int index) {
        return groups.get(index);
    }

    @Override
    public int size() {
        return groups.size();
    }

    public List<WorkingGroupBoard> getWorkingGroupsCreatedInPeriod() {
        List<WorkingGroupBoard> newlyCreated = new ArrayList<>();
        for (WorkingGroupBoard wg : groups) {
            if (hasBeenCreatedInPeriod(wg)) {
                newlyCreated.add(wg);
            }
        }
        return newlyCreated;
    }

    public List<WorkingGroupBoard> getWorkingGroupsCompletedInPeriod() {
        List<WorkingGroupBoard> completed = new ArrayList<>();
        for (WorkingGroupBoard wg : groups) {
            if (hasCompletedInPeriod(wg)) {
                completed.add(wg);
            }
        }
        return completed;
    }

    public boolean hasBeenCreatedInPeriod(WorkingGroupBoard wg) {
        return wg.createdAt().isAfter(from) && wg.createdAt().isBefore(to);
    }

    public boolean hasCompletedInPeriod(WorkingGroupBoard wg) {
        var last = getLastUpdate(wg, true);
        return last != null && last.status().equals("COMPLETE")
                && last.createdAt().isAfter(from) && last.createdAt().isBefore(to);
    }


    @SuppressWarnings("unused") // Called from the template
    public String getCompletionDate(WorkingGroupBoard wg) {
        if (!wg.isCompleted()) {
            return "";
        }

        for (StatusUpdate update : wg.getUpdates()) {
            if (update.createdAt() == null) {
                Log.infof("Update %s has no create date", wg.name());
            }
        }

        return DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(ZoneId.of("UTC")).format(getLastUpdate(wg, false).createdAt());
    }

    public StatusUpdate getLastUpdate(WorkingGroupBoard wg, boolean inPeriod) {

        if (wg.statusUpdates().nodes().isEmpty()) {
            return null;
        }

        wg.getUpdates().sort(Comparator.comparing(StatusUpdate::createdAt).reversed());
        var last = wg.getUpdates().getFirst();

        if (inPeriod && (last.createdAt().isBefore(from) || last.createdAt().isAfter(to))) {
            Log.debugf("Ignoring update %s for %s - not between %s and %s.", last.createdAt(), wg.name(), from, to);
            return null;
        }

        return last;
    }

    public boolean hasActivity(WorkingGroupBoard wg, boolean inPeriod) {
        return getLastUpdate(wg, inPeriod) != null
                || !getCompletedItems(wg).isEmpty()
                || !getNewItems(wg).isEmpty();
    }

    public List<Item> getNewItems(WorkingGroupBoard wg) {
        List<Item> items = wg.getItems();
        // Filter the items where the creation date is between the from and to dates.
        List<Item> news = new ArrayList<>();
        for (Item item : items) {
            if (item.content().createdAt().isAfter(from) && item.content().createdAt().isBefore(to)) {
                news.add(item);
            }
        }
        return news;
    }

    public List<Item> getCompletedItems(WorkingGroupBoard wg) {
        List<Item> items = wg.getItems();
        // Check that status is done, and the status update is between the from and to dates.
        Set<String> doneStatus = Set.of("done", "completed");
        List<Item> completed = new ArrayList<>();
        for (Item item : items) {
            if (doneStatus.contains(item.status().toLowerCase()) && item.statusDetails().updatedAt().isAfter(from) && item.statusDetails().updatedAt().isBefore(to)) {
                completed.add(item);
            }
        }
        return completed;
    }
}
