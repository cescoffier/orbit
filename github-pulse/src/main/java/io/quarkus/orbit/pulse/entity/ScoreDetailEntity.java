package io.quarkus.orbit.pulse.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "score_details")
public class ScoreDetailEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scored_pull_request_id", nullable = false)
    public ScoredPullRequestEntity scoredPullRequest;

    @Column(name = "rule_name", nullable = false)
    public String ruleName;

    public double points;

    @Column(name = "normalized_points")
    public double normalizedPoints;

    public double weight;

    public String reason;

    public String metadata;

    public static List<ScoreDetailEntity> findByScoredPullRequest(ScoredPullRequestEntity spr) {
        return list("scoredPullRequest", spr);
    }
}
