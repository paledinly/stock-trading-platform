/*
 * 스캐너가 조건을 만족한 종목을 감지한 기록 한 건을 DB 표(scanner_detection)에 담는 엔티티 파일이에요.
 * 감지 당시 가격·지표·점수와 설정/특징값 스냅샷(그 순간 값을 찍어 둔 사진)을 함께 저장해, 나중에 그대로 다시 볼 수 있어요.
 * ScannerEngine이 만들고, 조회 API·성과 분석·종가 추천·커버리지 진단 쪽에서 읽어요.
 */
package com.sunmo.stockplatform.scanner.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 감지 기록 한 건을 표현하는 엔티티(DB 표 한 줄을 그대로 옮긴 객체)예요.
 * 블랙박스 영상처럼 감지 순간의 가격·지표·설정 값을 그대로 찍어 두어, 나중에 설정이 바뀌어도 그때 상황을 확인할 수 있어요.
 */
@Entity
@Table(name = "scanner_detection")
public class ScannerDetection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 감지 이벤트 고유 id예요. 쿨다운 키 값과 이벤트 발행에도 같은 값을 써요.
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scanner_setting_id")
    private ScannerSetting setting;

    @Enumerated(EnumType.STRING)
    @Column(name = "scanner_type", nullable = false)
    private ScannerType type;

    // 감지 시각: 5분봉이 확정되는 시각(봉 시작 + 5분)이에요.
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    // 기록 시각: 이 객체를 만든 서버 시각이에요.
    // ⚠️ 확인 필요: 이름은 received_at(수신시각)이지만 원천 틱 수신시각이 아니라 감지 기록 생성 시각이에요.
    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "detected_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal detectedPrice;

    // 앞 6봉 중 가장 오래된 봉 종가(약 30분 전) 대비 변화율 %예요. 컬럼 이름과 달리 5분 변화율이 아니에요.
    @Column(name = "five_minute_change_rate", precision = 12, scale = 6)
    private BigDecimal changeRate;

    @Column(name = "volume_ratio", precision = 12, scale = 6)
    private BigDecimal volumeRatio;

    @Column(name = "current_5m_volume", nullable = false)
    private long currentVolume;

    @Column(name = "current_5m_trading_value", nullable = false, precision = 20, scale = 4)
    private BigDecimal currentValue;

    @Column(name = "daily_trading_value", precision = 20, scale = 4)
    private BigDecimal dailyValue;

    // 기본 점수(변화율 + 거래량 배수)예요. 상승 확률이 아니에요.
    @Column(name = "momentum_score", precision = 12, scale = 6)
    private BigDecimal score;

    // 감지 당시 설정 값 JSON이에요.
    @Column(name = "setting_snapshot", nullable = false, columnDefinition = "text")
    private String settingSnapshot;

    @Column(name = "feature_snapshot", columnDefinition = "text")
    private String featureSnapshot;

    @Column(name = "feature_version", length = 30)
    private String featureVersion;

    @Column(name = "detection_reason", columnDefinition = "text")
    private String detectionReason;

    // 기회/위험 점수(각 0~100)와 세부 내역 JSON, 점수 버전이에요.
    @Column(name = "opportunity_score", precision = 6, scale = 3)
    private BigDecimal opportunityScore;

    @Column(name = "risk_score", precision = 6, scale = 3)
    private BigDecimal riskScore;

    @Column(name = "score_breakdown", columnDefinition = "text")
    private String scoreBreakdown;

    @Column(name = "score_version", length = 30)
    private String scoreVersion;

    // 감지를 만든 원천 표시예요. 예: candle:봉 시작 시각
    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(name = "algorithm_version", nullable = false)
    private String algorithmVersion;

    // 한국 시간 기준 장 날짜예요.
    @Column(name = "market_session_date", nullable = false)
    private LocalDate sessionDate;

    /**
     * JPA(DB 매핑 도구)가 빈 객체를 만들 때만 쓰는 생성자예요.
     */
    protected ScannerDetection() {
    }

    /**
     * 하는 일: 감지 기록을 새로 만들어요.
     * 받는 것: event(감지 id), stock, setting, at(봉 확정 시각), price(감지 가격 = 봉 종가), change(변화율), ratio(거래량 배수),
     * volume/value(현재 5분봉 거래량·거래대금), daily(당일 누적 거래대금), score, snapshot(설정 JSON), source(원천 표시).
     * 돌려주는 것: 저장 전 감지 엔티티. 장 날짜는 at을 한국 시간으로 바꿔 정해요.
     */
    public ScannerDetection(UUID event, Stock stock, ScannerSetting setting, Instant at, BigDecimal price,
            BigDecimal change, BigDecimal ratio, long volume, BigDecimal value, BigDecimal daily,
            BigDecimal score, String snapshot, String source) {
        this.eventId = event;
        this.stock = stock;
        this.setting = setting;
        this.type = setting.getType();
        this.detectedAt = at;
        this.receivedAt = Instant.now();
        this.detectedPrice = price;
        this.changeRate = change;
        this.volumeRatio = ratio;
        this.currentVolume = volume;
        this.currentValue = value;
        this.dailyValue = daily;
        this.score = score;
        this.settingSnapshot = snapshot;
        this.sourceEventId = source;
        this.algorithmVersion = "momentum-v1";
        this.sessionDate = at.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    }

    /**
     * 하는 일: 특징값 버전과 JSON 스냅샷을 붙여요.
     * 받는 것: version, snapshot.
     * 돌려주는 것: 없음.
     */
    public void attachFeatureSnapshot(String version, String snapshot) {
        this.featureVersion = version;
        this.featureSnapshot = snapshot;
    }

    /**
     * 하는 일: 판정 근거 JSON을 기록해요.
     * 받는 것: reason.
     * 돌려주는 것: 없음.
     */
    public void recordReason(String reason) {
        this.detectionReason = reason;
    }

    /**
     * 하는 일: 기회/위험 점수, 점수 버전, 세부 내역 JSON을 붙여요.
     * 받는 것: opportunity, risk, version, breakdown.
     * 돌려주는 것: 없음.
     */
    public void attachOpportunityRisk(BigDecimal opportunity, BigDecimal risk, String version, String breakdown) {
        this.opportunityScore = opportunity;
        this.riskScore = risk;
        this.scoreVersion = version;
        this.scoreBreakdown = breakdown;
    }

    /**
     * 아래 get 메서드들은 저장된 값을 그대로 돌려주는 단순 조회용이에요.
     */
    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Stock getStock() {
        return stock;
    }

    public ScannerSetting getSetting() {
        return setting;
    }

    public ScannerType getType() {
        return type;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getReceivedAt() { return receivedAt; }

    public LocalDate getSessionDate() {
        return sessionDate;
    }

    public BigDecimal getDetectedPrice() {
        return detectedPrice;
    }

    public BigDecimal getChangeRate() {
        return changeRate;
    }

    public BigDecimal getVolumeRatio() {
        return volumeRatio;
    }

    public long getCurrentVolume() {
        return currentVolume;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public BigDecimal getDailyValue() {
        return dailyValue;
    }

    public BigDecimal getScore() {
        return score;
    }

    public String getSettingSnapshot() {
        return settingSnapshot;
    }

    public String getFeatureSnapshot() {
        return featureSnapshot;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public String getDetectionReason() {
        return detectionReason;
    }

    public BigDecimal getOpportunityScore() {
        return opportunityScore;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public String getScoreBreakdown() {
        return scoreBreakdown;
    }

    public String getScoreVersion() {
        return scoreVersion;
    }
}
