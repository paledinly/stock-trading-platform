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
    @Column(name = "execution_mode", nullable = false, length = 20)
    private String executionMode = "LEGACY";
    @Column(name = "evaluated_as_of")
    private Instant evaluatedAsOf;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "settings_snapshot", nullable = false, columnDefinition = "text")
    private String settingsSnapshot = "{}";
    @Column(name = "settings_hash", length = 64)
    private String settingsHash;
    @Column(name = "data_version", nullable = false, length = 40)
    private String dataVersion = "legacy-unversioned";
    @Column(name = "request_key", unique = true, length = 100)
    private String requestKey;
    protected ClosingRecommendationRun() { }
    public ClosingRecommendationRun(LocalDate date, Instant at, String version, String snapshot) {
        recommendationDate = date;
        generatedAt = at;
        strategyVersion = version;
        responseSnapshot = snapshot;
    }
    public String getResponseSnapshot() { return responseSnapshot; }
    public ClosingRecommendationRun(LocalDate date, Instant at, String version, String mode, Instant asOf,
            String settings, String hash, String key) {
        this(date, at, version, "{}");
        executionMode = mode;
        evaluatedAsOf = asOf;
        settingsSnapshot = settings;
        settingsHash = hash;
        requestKey = key;
        dataVersion = "stored-market-asof-v1";
    }
    public void complete(String response, Instant at) { responseSnapshot = response; completedAt = at; }
    public void recordDataVersion(String version) { dataVersion = version; }
    public Long getId() { return id; }
    public LocalDate getRecommendationDate() { return recommendationDate; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getExecutionMode() { return executionMode; }
    public Instant getEvaluatedAsOf() { return evaluatedAsOf; }
    public Instant getCompletedAt() { return completedAt; }
    public String getSettingsSnapshot() { return settingsSnapshot; }
    public String getSettingsHash() { return settingsHash; }
    public String getDataVersion() { return dataVersion; }
    public String getRequestKey() { return requestKey; }
}
