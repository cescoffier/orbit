package io.quarkus.orbit.pulse.entity;

import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "pr_classifications")
public class PrClassification extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "repo_identifier", nullable = false)
    public String repoIdentifier;

    @Column(name = "pr_number", nullable = false)
    public int prNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    public PrCategory category;

    @Column(name = "classified_at", nullable = false)
    public Instant classifiedAt;

    public static Optional<PrCategory> findCategory(String repoIdentifier, int prNumber) {
        return find("repoIdentifier = ?1 and prNumber = ?2", repoIdentifier, prNumber)
                .firstResultOptional()
                .map(e -> ((PrClassification) e).category);
    }

    public static void store(String repoIdentifier, int prNumber, PrCategory category) {
        PrClassification record = new PrClassification();
        record.repoIdentifier = repoIdentifier;
        record.prNumber = prNumber;
        record.category = category;
        record.classifiedAt = Instant.now();
        record.persist();
    }
}
