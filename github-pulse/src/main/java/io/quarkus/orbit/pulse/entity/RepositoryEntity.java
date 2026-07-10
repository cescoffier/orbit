package io.quarkus.orbit.pulse.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "repositories", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "name"}))
public class RepositoryEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String owner;

    @Column(nullable = false)
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RepositorySource source;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "repository_artifacts", joinColumns = @JoinColumn(name = "repository_id"))
    @Column(name = "artifact")
    public List<String> artifacts = new ArrayList<>();

    public String identifier() {
        return owner + "/" + name;
    }

    public static Optional<RepositoryEntity> findByOwnerAndName(String owner, String name) {
        return find("owner = ?1 and name = ?2", owner, name).firstResultOptional();
    }

    public static List<RepositoryEntity> findBySource(RepositorySource source) {
        return list("source", source);
    }
}
