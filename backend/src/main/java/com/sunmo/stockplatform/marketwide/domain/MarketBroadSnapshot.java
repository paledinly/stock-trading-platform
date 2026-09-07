package com.sunmo.stockplatform.marketwide.domain;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "market_broad_snapshot", uniqueConstraints = @UniqueConstraint(
        name = "uk_market_broad_snapshot_bucket_stock", columnNames = { "session_date", "captured_at", "stock_id" }))
public class MarketBroadSnapshot {
    public static final String SOURCE_VERSION = "broad-ranking-v1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id")
    private Stock stock;
    @Column(name = "current_price", precision = 20, scale = 4)
    private BigDecimal currentPrice;
    @Column(name = "change_rate", precision = 12, scale = 6)
    private BigDecimal changeRate;
    @Column(name = "accumulated_volume")
    private Long accumulatedVolume;
    @Column(name = "accumulated_trading_value", precision = 20, scale = 4)
    private BigDecimal accumulatedTradingValue;
    @Column(name = "day_open", precision = 20, scale = 4)
    private BigDecimal dayOpen;
    @Column(name = "day_high", precision = 20, scale = 4)
    private BigDecimal dayHigh;
    @Column(name = "day_low", precision = 20, scale = 4)
    private BigDecimal dayLow;
    @Column(name = "trade_strength", precision = 12, scale = 6)
    private BigDecimal tradeStrength;
    @Column(name = "broad_score", nullable = false, precision = 12, scale = 6)
    private BigDecimal broadScore;
    @Column(name = "ranking_sources", nullable = false, columnDefinition = "text")
    private String rankingSources;
    @Enumerated(EnumType.STRING)
    @Column(name = "data_quality", nullable = false, length = 20)
    private BroadSnapshotQuality dataQuality;
    @Enumerated(EnumType.STRING)
    @Column(name = "collection_status", nullable = false, length = 20)
    private BroadSnapshotStatus collectionStatus;
    @Column(name = "exclusion_reason", columnDefinition = "text")
    private String exclusionReason;
    @Column(name = "quoted_at")
    private Instant quotedAt;
    @Column(name = "source_version", nullable = false, length = 40)
    private String sourceVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MarketBroadSnapshot() {
    }

    public MarketBroadSnapshot(LocalDate sessionDate, Instant capturedAt, Stock stock) {
        this.sessionDate = sessionDate;
        this.capturedAt = capturedAt;
        this.stock = stock;
        this.sourceVersion = SOURCE_VERSION;
    }

    public void update(StockQuote quote, BigDecimal score, String sources, BigDecimal strength, String error) {
        broadScore = score == null ? BigDecimal.ZERO : score;
        rankingSources = sources;
        tradeStrength = strength;
        exclusionReason = error;
        if (quote == null) {
            clearQuote();
            dataQuality = BroadSnapshotQuality.INSUFFICIENT;
            collectionStatus = BroadSnapshotStatus.QUOTE_FAILED;
            return;
        }
        currentPrice = quote.currentPrice();
        changeRate = quote.changeRate();
        accumulatedVolume = quote.accumulatedVolume();
        accumulatedTradingValue = quote.accumulatedTradingValue();
        dayOpen = quote.openPrice();
        dayHigh = quote.highPrice();
        dayLow = quote.lowPrice();
        quotedAt = quote.quotedAt();
        dataQuality = BroadSnapshotQuality.BROAD_C;
        collectionStatus = BroadSnapshotStatus.COLLECTED;
        exclusionReason = null;
    }

    private void clearQuote() {
        currentPrice = null;
        changeRate = null;
        accumulatedVolume = null;
        accumulatedTradingValue = null;
        dayOpen = null;
        dayHigh = null;
        dayLow = null;
        quotedAt = null;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public LocalDate getSessionDate() { return sessionDate; }
    public Instant getCapturedAt() { return capturedAt; }
    public Stock getStock() { return stock; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public BigDecimal getChangeRate() { return changeRate; }
    public Long getAccumulatedVolume() { return accumulatedVolume; }
    public BigDecimal getAccumulatedTradingValue() { return accumulatedTradingValue; }
    public BigDecimal getDayOpen() { return dayOpen; }
    public BigDecimal getDayHigh() { return dayHigh; }
    public BigDecimal getDayLow() { return dayLow; }
    public BigDecimal getTradeStrength() { return tradeStrength; }
    public BigDecimal getBroadScore() { return broadScore; }
    public String getRankingSources() { return rankingSources; }
    public BroadSnapshotQuality getDataQuality() { return dataQuality; }
    public BroadSnapshotStatus getCollectionStatus() { return collectionStatus; }
    public String getExclusionReason() { return exclusionReason; }
    public Instant getQuotedAt() { return quotedAt; }
    public String getSourceVersion() { return sourceVersion; }
}
