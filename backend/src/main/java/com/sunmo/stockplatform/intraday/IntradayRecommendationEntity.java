package com.sunmo.stockplatform.intraday;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "intraday_recommendation")
public class IntradayRecommendationEntity {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private LocalDate sessionDate;
    @Column(nullable = false, updatable = false, length = 12) private String stockCode;
    @Column(nullable = false, updatable = false, length = 24) private String setup;
    @Column(nullable = false, updatable = false) private Instant recommendedAt;
    @Column(nullable = false, updatable = false, columnDefinition = "text") private String snapshot;
    @Column(nullable = false, columnDefinition = "text") private String outcome;
    @Column(nullable = false) private boolean trackingComplete;
    @Version private long version;
    protected IntradayRecommendationEntity() {}
    public IntradayRecommendationEntity(IntradayModel.Signal signal, String snapshot, String outcome) {
        id = signal.id(); stockCode = signal.stockCode(); setup = signal.setup(); recommendedAt = signal.recommendedAt();
        sessionDate = recommendedAt.atZone(IntradayEngine.ZONE).toLocalDate(); this.snapshot = snapshot; this.outcome = outcome;
    }
    public UUID getId() { return id; }
    public String getSnapshot() { return snapshot; }
    public String getOutcome() { return outcome; }
    public void updateOutcome(String outcome, boolean trackingComplete) { this.outcome = outcome; this.trackingComplete = trackingComplete; }
}
