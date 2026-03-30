package io.quarkus.orbit.pulse.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_prs")
public class ProcessedPr extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "repo_identifier", nullable = false)
    public String repoIdentifier;

    @Column(name = "pr_number", nullable = false)
    public int prNumber;

    @Column(name = "analyzed_at", nullable = false)
    public Instant analyzedAt;

    public static boolean alreadyProcessed(String repoIdentifier, int prNumber) {
        return count("repoIdentifier = ?1 and prNumber = ?2", repoIdentifier, prNumber) > 0;
    }

    public static void markProcessed(String repoIdentifier, int prNumber) {
        ProcessedPr record = new ProcessedPr();
        record.repoIdentifier = repoIdentifier;
        record.prNumber = prNumber;
        record.analyzedAt = Instant.now();
        record.persist();
    }
}
