/*
 * 스캐너 한 개의 조건(최소 변화율, 거래량 배수, 거래대금, 가격, ETF 포함, 쿨다운)을 DB 표(scanner_setting)에 담는 엔티티 파일이에요.
 * ScannerQueryService가 만들고 고치며, ScannerEngine과 백테스트가 읽어요.
 */
package com.sunmo.stockplatform.scanner.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 스캐너 설정 한 개를 표현하는 엔티티(DB 표 한 줄을 그대로 옮긴 객체)예요.
 * 알람 시계 설정처럼 '어떤 조건이면 울릴지, 울린 뒤 몇 초 쉬었다가 다시 울릴지'를 적어 둬요.
 */
@Entity
@Table(name = "scanner_setting")
public class ScannerSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(nullable = false, length = 100)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "scanner_type", nullable = false)
    private ScannerType type;
    @Column(name = "min_change_rate", nullable = false, precision = 12, scale = 6)
    private BigDecimal minChangeRate;
    @Column(name = "min_volume_ratio", nullable = false, precision = 12, scale = 6)
    private BigDecimal minVolumeRatio;
    @Column(name = "min_5m_trading_value", nullable = false, precision = 20, scale = 4)
    private BigDecimal minFiveMinuteTradingValue;
    @Column(name = "min_daily_trading_value", nullable = false, precision = 20, scale = 4)
    private BigDecimal minDailyTradingValue;
    @Column(name = "min_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal minPrice;
    @Column(name = "include_etf", nullable = false)
    private boolean includeEtf;
    // 쿨다운(같은 종목을 다시 알리기 전에 쉬는 시간, 초)이에요.
    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    // 낙관적 잠금 번호예요. 저장할 때마다 올라가서 동시에 고친 경우를 알아낼 수 있어요.
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * JPA(DB 매핑 도구)가 빈 객체를 만들 때만 쓰는 생성자예요.
     */
    protected ScannerSetting() {
    }

    /**
     * 하는 일: 새 설정을 활성 상태로 만들어요. 종류(type)는 만든 뒤 바꿀 수 없어요.
     * 받는 것: owner, name, type, change(최소 변화율 %), ratio(최소 거래량 배수), fiveValue/dailyValue(최소 5분·당일 거래대금),
     * minPrice, includeEtf, cooldown(초).
     * 돌려주는 것: 새 설정 엔티티.
     */
    public ScannerSetting(Long owner, String name, ScannerType type, BigDecimal change, BigDecimal ratio,
            BigDecimal fiveValue, BigDecimal dailyValue, BigDecimal minPrice, boolean includeEtf, int cooldown) {
        this.ownerId = owner;
        this.name = name;
        this.type = type;
        update(name, change, ratio, fiveValue, dailyValue, minPrice, includeEtf, cooldown, true);
    }

    /**
     * 하는 일: 종류를 뺀 조건 값들과 활성 여부를 한꺼번에 바꿔요.
     * 받는 것: 새 이름, 조건 값들, active.
     * 돌려주는 것: 없음.
     */
    public void update(String name, BigDecimal change, BigDecimal ratio, BigDecimal fiveValue, BigDecimal dailyValue,
            BigDecimal minPrice, boolean includeEtf, int cooldown, boolean active) {
        this.name = name;
        this.minChangeRate = change;
        this.minVolumeRatio = ratio;
        this.minFiveMinuteTradingValue = fiveValue;
        this.minDailyTradingValue = dailyValue;
        this.minPrice = minPrice;
        this.includeEtf = includeEtf;
        this.cooldownSeconds = cooldown;
        this.active = active;
    }

    /**
     * 하는 일: 처음 저장하기 직전에 생성·수정 시각을 채워요.
     */
    @PrePersist
    void create() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /**
     * 하는 일: 수정 저장 직전에 수정 시각을 새로 고쳐요.
     */
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /**
     * 아래 get/is 메서드들은 저장된 값을 그대로 돌려주는 단순 조회용이에요.
     */
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ScannerType getType() {
        return type;
    }

    public BigDecimal getMinChangeRate() {
        return minChangeRate;
    }

    public BigDecimal getMinVolumeRatio() {
        return minVolumeRatio;
    }

    public BigDecimal getMinFiveMinuteTradingValue() {
        return minFiveMinuteTradingValue;
    }

    public BigDecimal getMinDailyTradingValue() {
        return minDailyTradingValue;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public boolean isIncludeEtf() {
        return includeEtf;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }
}
