package io.quarkus.orbit.pulse.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "scored_pull_requests", uniqueConstraints = @UniqueConstraint(columnNames = {"repository_id", "pr_number"}))
public class ScoredPullRequestEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    public RepositoryEntity repository;

    @Column(name = "pr_number", nullable = false)
    public int prNumber;

    public String title;

    public String author;

    public String url;

    @Column(name = "total_score", nullable = false)
    public double totalScore;

    @Column(name = "scored_at", nullable = false)
    public Instant scoredAt;

    @Column(name = "category")
    @Enumerated(EnumType.STRING)
    public PrCategory category;

    @Column(name = "summary")
    public String summary;

    @OneToMany(mappedBy = "scoredPullRequest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<ScoreDetailEntity> details = new ArrayList<>();

    @ManyToMany(mappedBy = "pullRequests", fetch = FetchType.LAZY)
    public List<ReleaseEntity> releases = new ArrayList<>();

    public static boolean exists(RepositoryEntity repo, int prNumber) {
        return count("repository = ?1 and prNumber = ?2", repo, prNumber) > 0;
    }

    public static Optional<ScoredPullRequestEntity> findByRepoAndNumber(RepositoryEntity repo, int prNumber) {
        return find("repository = ?1 and prNumber = ?2", repo, prNumber).firstResultOptional();
    }

    public static List<ScoredPullRequestEntity> findByRepo(RepositoryEntity repo, int limit) {
        return find("repository = ?1 order by totalScore desc", repo)
                .page(0, limit)
                .list();
    }

    public static List<ScoredPullRequestEntity> findByRepo(RepositoryEntity repo) {
        return findByRepo(repo, 20);
    }
}
