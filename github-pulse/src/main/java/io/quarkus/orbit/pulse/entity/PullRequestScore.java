package io.quarkus.orbit.pulse.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pull_request_scores", uniqueConstraints = @UniqueConstraint(columnNames = {"repository_id", "pr_number"}))
public class PullRequestScore extends PanacheEntityBase {

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

    @OneToMany(mappedBy = "pullRequestScore", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<ScoreDetail> details = new ArrayList<>();

    public static boolean exists(RepositoryEntity repo, int prNumber) {
        return count("repository = ?1 and prNumber = ?2", repo, prNumber) > 0;
    }

    public static List<PullRequestScore> findByRepo(RepositoryEntity repo, int limit) {
        return find("repository = ?1 order by totalScore desc", repo)
                .page(0, limit)
                .list();
    }

    public static List<PullRequestScore> findByRepo(RepositoryEntity repo) {
        return findByRepo(repo, 20);
    }
}
