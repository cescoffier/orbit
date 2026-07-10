package io.quarkus.orbit.pulse.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Entity
@Table(name = "releases", uniqueConstraints = @UniqueConstraint(columnNames = {"repository_id", "tag"}))
public class ReleaseEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    public RepositoryEntity repository;

    @Column(nullable = false)
    public String tag;

    @Column(name = "analyzed_at", nullable = false)
    public Instant analyzedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "release_pull_requests",
            joinColumns = @JoinColumn(name = "release_id"),
            inverseJoinColumns = @JoinColumn(name = "scored_pull_request_id")
    )
    public Set<ScoredPullRequestEntity> pullRequests = new HashSet<>();

    public static Optional<ReleaseEntity> findByRepoAndTag(RepositoryEntity repo, String tag) {
        return find("repository = ?1 and tag = ?2", repo, tag).firstResultOptional();
    }

    public static List<ReleaseEntity> findByRepo(RepositoryEntity repo) {
        return list("repository", repo);
    }
}
