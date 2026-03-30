package io.quarkus.orbit.wg.detection.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class DetectionRun {

    public String id;

    public Instant startedAt;

    public Instant completedAt;

    public String status = "RUNNING"; // RUNNING, COMPLETED, FAILED

    public int totalCandidates = 0;

    public int pendingReview = 0;

    public int approved = 0;

    public int rejected = 0;

    public String errorMessage;

    public int lookbackDays = 14;

    public String progressMessage;

    public int progressPercent = 0;

    public Map<String, List<IssueOrPR>> results = new ConcurrentHashMap<>();

    public List<String> workingGroupsWithoutRepositories = new ArrayList<>();

    public static DetectionRun createNew(int lookbackDays) {
        DetectionRun run = new DetectionRun();
        run.id = Long.toString(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        run.startedAt = Instant.now();
        run.lookbackDays = lookbackDays;
        run.status = "RUNNING";
        return run;
    }

    public void markCompleted() {
        this.status = "COMPLETED";
        this.completedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = "FAILED";
        this.completedAt = Instant.now();
        this.errorMessage = error;
    }

    public void updateProgress(String message, int percent) {
        this.progressMessage = message;
        this.progressPercent = percent;
    }

    public void addCandidatesForWorkingGroup(String wg, List<IssueOrPR> candidates) {
        results.compute(wg, (key, existing) -> {
            if (existing == null) {
                return candidates;
            } else {
                existing.addAll(candidates);
                return existing;
            }
        });
    }
}
