package io.quarkus.orbit.wg.detection.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.logging.Log;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

public record WorkingGroupBoard(
        String id,
        @JsonProperty("title")
        String name,
        @JsonProperty("readme")
        String proposal,
        String url,
        Instant createdAt,
        Instant updatedAt,
        StatusUpdates statusUpdates,
        Items items) {

    public List<Item> getItems() {
        return items.nodes();
    }

    public List<StatusUpdate> getUpdates() {
        return statusUpdates.nodes();
    }

    public Status getStatus() {
        if (getUpdates().isEmpty()) {
            return Status.PAUSED;
        }

        var update = getUpdates().stream()
                .max(Comparator.comparing(StatusUpdate::createdAt))
                .orElseThrow();

        // Is it completed?
        if (update.status().equals("COMPLETE")) {
            return Status.COMPLETE;
        }

        // Is it inactive?
        if (update.status().equals("INACTIVE")) {
            return Status.PAUSED;
        }

        // Is it staled?
        // Months is an unsupported unit, so using days
        if (update.createdAt().isBefore(Instant.now().minus(60, ChronoUnit.DAYS))) {
            return Status.STALED;
        }

        switch (update.status()) {
            case "ON_TRACK" -> {
                return Status.ON_TRACK;
            }
            case "AT_RISK" -> {
                return Status.AT_RISK;
            }
            case "OFF_TRACK" -> {
                return Status.OFF_TRACK;
            }
        }

        Log.warn("Unable to determine status of working group " + url + ", using INACTIVE as default");
        return Status.OFF_TRACK;

    }

    public boolean isCompleted() {
        return getStatus() == Status.COMPLETE;
    }

    public boolean isPaused() {
        return getStatus() == Status.PAUSED;
    }

    public boolean isLTS() {
        return name.toLowerCase().trim().endsWith("lts");
    }

    public boolean shouldProcess() {
        return ! isCompleted() && ! isPaused() && ! isLTS();
    }
}