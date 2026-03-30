package io.quarkus.orbit.wg.detection.resource;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import io.quarkus.orbit.wg.detection.model.DetectionRun;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;
import io.quarkus.orbit.wg.detection.service.AssociationService;
import io.quarkus.orbit.wg.detection.service.DetectionService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

import java.util.List;
import java.util.Map;

@Path("/api/detection")
public class DetectionResource {

    @Inject
    DetectionService detectionService;

    @Inject
    AssociationService associationService;

    @ConfigProperty(name = "detection.lookback-days", defaultValue = "14")
    int maxLookbackDays;

    @POST
    @Path("/start")
    public DetectionRun startDetection(@RestQuery @DefaultValue("-1") int lookbackDays) {
        if (lookbackDays < 0) {
            lookbackDays = maxLookbackDays;
        }
        // Start detection asynchronously - returns immediately
        return detectionService.startDetection(lookbackDays);
    }

    @GET
    @Path("/{runId}")
    public DetectionRun getDetectionRun(@RestPath String runId) {
        DetectionRun run = detectionService.getDetectionRun(runId);
        if (run == null) {
            throw new NotFoundException("Detection run not found");
        }
        return run;
    }

    @GET
    @Path("/{runId}/candidates")
    public Map<String, List<IssueOrPR>> getCandidates(@RestPath String runId) {
        return detectionService.getDetectionRun(runId).results;
    }

    @POST
    @Path("/{runId}/apply")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> applyApprovedAssociations(
            @RestPath String runId,
            Map<String, List<IssueOrPR>> approvedByWorkingGroup) {

        Log.infof("Applying approved associations for run %s: %d working group(s)",
                runId, approvedByWorkingGroup.size());

        int applied = associationService.applyApprovedAssociations(runId, approvedByWorkingGroup);
        return Map.of(
            "applied", applied,
            "runId", runId
        );
    }
}
