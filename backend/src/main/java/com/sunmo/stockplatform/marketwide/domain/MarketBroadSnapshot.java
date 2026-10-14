/*
 * 전체 시장 스캔 한 회차에서 후보 종목 하나의 시세·점수·순위 출처를 DB에 남기는 엔티티(DB 표의 한 줄과 짝을 이루는 객체)예요.
 * 같은 거래일·같은 수집 시각·같은 종목은 한 줄만 있도록 막아 두었어요.
 * BroadSnapshotService가 저장하고, 커버리지 집계와 종가 추천(ClosingRecommendationService)이 읽어 가요.
 */
package com.sunmo.stockplatform.marketwide.domain;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * BROAD 스캔에서 본 후보 종목 한 개의 "그 순간 기록"을 담당해요.
 * 매 회차 출석부에 학생마다 그때의 키·몸무게·특이사항을 한 줄씩 적어 두는 것과 같아요.
 * capturedAt은 스캔 회차 시각, quotedAt은 시세 자체가 관측된 시각이라 서로 달라요.
 */
@Entity
@Table(name = "market_broad_snapshot", uniqueConstraints = @UniqueConstraint(
        name = "uk_market_broad_snapshot_bucket_stock", columnNames = { "session_date", "captured_at", "stock_id" }))
public class MarketBroadSnapshot {
    /** 이 기록을 만든 수집 로직의 버전 표시예요. 나중에 로직이 바뀌면 옛 기록과 구분하려고 남겨요. */
    public static final String SOURCE_VERSION = "broad-ranking-v2-preserved";

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
    @Column(name = "quote_source", nullable = false, length = 40)
    private String quoteSource = "LEGACY_REST";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA(DB 기록을 객체로 바꿔 주는 도구)가 DB에서 읽어 올 때만 쓰는 빈 생성자예요. */
    protected MarketBroadSnapshot() {
    }

    /**
     * 하는 일: 거래일·수집 시각·종목만 정한 새 스냅샷을 만들어요. 시세와 점수는 update/updateData로 채워요.
     * 받는 것: 거래일, 스캔 회차 시각, 종목.
     * 돌려주는 것: 새 MarketBroadSnapshot(생성자라 따로 반환값은 없어요).
     */
    public MarketBroadSnapshot(LocalDate sessionDate, Instant capturedAt, Stock stock) {
        this.sessionDate = sessionDate;
        this.capturedAt = capturedAt;
        this.stock = stock;
        this.sourceVersion = SOURCE_VERSION;
    }

    /**
     * 하는 일: REST 조회로 받은 시세 객체를 BroadQuoteData로 바꾼 뒤 updateData에 넘겨요.
     * 받는 것: 시세(없으면 null), BROAD 점수, 순위 출처 문자열, 체결강도, 오류/제외 사유.
     * 돌려주는 것: 없음(이 스냅샷의 값을 바꿔요).
     */
    public void update(StockQuote quote, BigDecimal score, String sources, BigDecimal strength, String error) {
        updateData(quote == null ? null : BroadQuoteData.fromQuote(quote, "REST"), score, sources, strength, error);
    }

    /**
     * 하는 일: 점수·출처·시세 값을 채우고, 시세가 충분한지에 따라 품질과 수집 상태를 정해요.
     * 받는 것: 시세 묶음(없으면 null), BROAD 점수(없으면 0), 순위 출처, 체결강도, 오류/제외 사유.
     * 돌려주는 것: 없음(이 스냅샷의 값을 바꿔요).
     */
    public void updateData(BroadQuoteData data, BigDecimal score, String sources, BigDecimal strength, String error) {
        // 준비: 시세가 있든 없든 공통으로 남길 점수와 출처 정보를 먼저 기록해요.
        sourceVersion = SOURCE_VERSION;
        broadScore = score == null ? BigDecimal.ZERO : score;
        rankingSources = sources;
        tradeStrength = strength;
        exclusionReason = error;
        quoteSource = data == null ? "NONE" : data.source();
        // 시세가 아예 없으면 예전 값이 남지 않도록 비우고 "수집 실패"로 표시해요.
        if (data == null) {
            clearQuote();
            dataQuality = BroadSnapshotQuality.INSUFFICIENT;
            collectionStatus = BroadSnapshotStatus.QUOTE_FAILED;
            return;
        }
        // 처리: 받은 시세 값을 그대로 옮겨요. 체결강도는 시세 쪽에 값이 있을 때만 덮어써요.
        currentPrice = data.price();
        changeRate = data.changeRate();
        accumulatedVolume = data.volume();
        accumulatedTradingValue = data.tradingValue();
        dayOpen = data.open();
        dayHigh = data.high();
        dayLow = data.low();
        if (data.tradeStrength() != null) tradeStrength = data.tradeStrength();
        quotedAt = data.observedAt();
        // 결과: 핵심 값이 다 있으면 수집 성공으로 보고 제외 사유를 지워요.
        dataQuality = data.complete() ? BroadSnapshotQuality.BROAD_C : BroadSnapshotQuality.INSUFFICIENT;
        collectionStatus = data.complete() ? BroadSnapshotStatus.COLLECTED : BroadSnapshotStatus.QUOTE_FAILED;
        if (data.complete()) exclusionReason = null;
    }

    /**
     * 하는 일: 시세 관련 값(가격, 등락률, 거래량, 거래대금, 시가·고가·저가, 관측 시각)을 모두 비워요.
     * 받는 것: 없음.
     * 돌려주는 것: 없음.
     */
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

    /**
     * 하는 일: 처음 DB에 저장되기 직전에 생성·수정 시각을 현재 시각으로 채워요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /**
     * 하는 일: DB에 수정 내용이 저장되기 직전에 수정 시각을 현재 시각으로 바꿔요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** 저장된 값을 그대로 꺼내 주는 읽기 전용 메서드들이에요(받는 것 없음, 각 필드 값을 돌려줘요). */
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
    public String getQuoteSource() { return quoteSource; }
}
