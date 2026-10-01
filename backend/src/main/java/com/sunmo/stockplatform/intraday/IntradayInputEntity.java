package com.sunmo.stockplatform.intraday;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "intraday_input")
public class IntradayInputEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private LocalDate sessionDate;
    @Column(nullable = false, updatable = false, length = 12) private String stockCode;
    @Column(nullable = false, updatable = false) private Instant receivedAt;
    @Column(nullable = false, updatable = false) private Instant evaluatedAt;
    @Column(nullable = false, updatable = false, columnDefinition = "text") private String payload;
    protected IntradayInputEntity() {}
    public IntradayInputEntity(IntradayModel.Input input, String payload) {
        sessionDate = input.observation().tick().businessDate(); stockCode = input.observation().tick().stockCode();
        receivedAt = input.observation().receivedAt(); evaluatedAt = input.evaluatedAt(); this.payload = payload;
    }
    public String getPayload() { return payload; }
    public Long getId() { return id; }
}
