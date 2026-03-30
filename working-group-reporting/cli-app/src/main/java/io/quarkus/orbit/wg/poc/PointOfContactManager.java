package io.quarkus.orbit.wg.poc;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import io.quarkus.orbit.wg.WorkingGroups;
import io.quarkus.orbit.wg.graphql.WorkingGroupBoard;

import java.util.List;
import java.util.concurrent.ExecutionException;

@Singleton
public class PointOfContactManager {

    @Inject
    WorkingGroups workingGroups;

    @ActivateRequestContext
    @Transactional
    public void setPointOfContact(String workingGroup, String contact) {
        WorkingGroupPointOfContact poc = WorkingGroupPointOfContact.<WorkingGroupPointOfContact>find("name", workingGroup)
                .firstResultOptional()
                .orElseGet(() -> {
                    var wg = new WorkingGroupPointOfContact();
                    wg.name = workingGroup;
                    return wg;
                });
        poc.pointOfContact = contact;
        poc.persist();
        Log.infof("Point of contact for %s set to %s", workingGroup, contact);
    }


    public void printPointOfContact(String workingGroup) {
        WorkingGroupPointOfContact.<WorkingGroupPointOfContact>find("name", workingGroup)
                .firstResultOptional()
                .ifPresentOrElse(
                        poc -> Log.infof("\uD83D\uDFE2 %s => %s", workingGroup, poc.pointOfContact),
                        () -> Log.warnf("\uD83D\uDD34 %s => %s", workingGroup, "No point of contact"));
    }

    public void printAllWorkingGroups() {
        try {
            List<WorkingGroupBoard> groups = workingGroups.fetchAllWorkingGroups();
            for (WorkingGroupBoard group : groups) {
                WorkingGroupPointOfContact.<WorkingGroupPointOfContact>find("name", group.name())
                        .firstResultOptional()
                        .ifPresentOrElse(
                                poc -> Log.infof("\uD83D\uDFE2 %s => %s", group.name(), poc.pointOfContact),
                                () -> Log.warnf("\uD83D\uDD34 %s => %s", group.name(), "No point of contact"));
            }
        } catch (ExecutionException | InterruptedException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @ActivateRequestContext
    public String getPointOfContact(String name) {
        WorkingGroupPointOfContact result = WorkingGroupPointOfContact.<WorkingGroupPointOfContact>find("name", name).firstResult();
        return result == null ? null : result.pointOfContact;
    }
}
