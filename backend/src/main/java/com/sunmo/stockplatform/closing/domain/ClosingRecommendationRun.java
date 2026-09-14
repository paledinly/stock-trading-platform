package com.sunmo.stockplatform.closing.domain;

import jakarta.persistence.*;
import java.time.*;

/** Immutable generation audit; observation candidates never enter overnight position tracking. */
@Entity
@Table(name = "closing_recommendation_run")
public class ClosingRecommendationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "recommendation_date", nullable = false)
    private LocalDate recommendationDate;
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
    @Column(name = "strategy_version", nullable = false, length = 40)
    private String strategyVersion;
    @Column(name = "response_snapshot", nullable = false, columnDefinition = "text")
    private String responseSnapshot;
    protected ClosingRecommendationRun() { }
    public ClosingRecommendationRun(LocalDate date, Instant at, String version, String snapshot) {
        recommendationDate = date;
        generatedAt = at;
        strategyVersion = version;
        responseSnapshot = snapshot;
    }
    public String getResponseSnapshot() { return responseSnapshot; }
}
