package io.quarkus.orbit.wg.poc;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

@Entity
public class WorkingGroupPointOfContact extends PanacheEntity {

    public String name;

    public String pointOfContact;

}
