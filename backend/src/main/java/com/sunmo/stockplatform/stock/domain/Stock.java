/*
 * 종목 한 개의 기본 정보(코드, 이름, 시장, 상품 유형, 관리/거래정지 여부)를 DB stock 테이블과 연결하는 파일이에요.
 * JPA(자바 객체와 DB 표를 자동으로 이어 주는 기술)가 이 클래스로 행을 읽고 써요.
 * 종목 검색, 관심종목, 거래 기록, 시장 스캔 등 거의 모든 기능이 이 종목 정보를 참조해요.
 */
package com.sunmo.stockplatform.stock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 종목 한 건을 나타내는 엔티티(DB 표의 한 줄과 연결된 객체)예요.
 * 주민등록부의 한 사람 카드처럼 고유번호(종목코드)와 이름, 소속(시장), 상태(관리·정지)를 적어 두고 명단이 갱신되면 고쳐 적어요.
 */
@Entity
@Table(name = "stock")
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "stock_code", nullable = false, unique = true, length = 12)
    private String stockCode;
    @Column(name = "standard_code", length = 20)
    private String standardCode;
    @Column(name = "stock_name", nullable = false, length = 120)
    private String stockName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Market market;
    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 30)
    private MarketType marketType;
    @Column(name = "is_etf", nullable = false)
    private boolean etf;
    @Column(name = "is_etn", nullable = false)
    private boolean etn;
    @Column(name = "is_managed", nullable = false)
    private boolean managed;
    @Column(name = "is_trading_halted", nullable = false)
    private boolean tradingHalted;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    @Column(name = "master_synced_at", nullable = false)
    private Instant masterSyncedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA가 DB에서 읽은 값을 채울 때 쓰는 빈 생성자예요. 직접 호출하지 않아요. */
    protected Stock() {
    }

    /**
     * 마스터 파일에서 처음 본 종목을 새로 만들어요.
     * 받는 것: 종목코드, 표준코드, 이름, 시장, 상품 유형, 관리종목 여부, 거래정지 여부, 동기화 시각.
     * 돌려주는 것: 활성 상태(active=true)의 새 종목 객체.
     */
    public Stock(String stockCode, String standardCode, String stockName, Market market,
            MarketType marketType, boolean managed, boolean tradingHalted, Instant syncedAt) {
        this.stockCode = stockCode;
        this.standardCode = standardCode;
        this.stockName = stockName;
        this.market = market;
        applyMarketType(marketType);
        this.managed = managed;
        this.tradingHalted = tradingHalted;
        this.active = true;
        this.masterSyncedAt = syncedAt;
    }

    /**
     * 이미 있는 종목을 최신 마스터 내용으로 고쳐 적어요(종목코드는 바꾸지 않아요).
     * 받는 것: 표준코드, 이름, 시장, 상품 유형, 관리종목 여부, 거래정지 여부, 동기화 시각.
     * 돌려주는 것: 없음(자기 자신의 값을 바꿔요). 다시 활성 상태로 만들어요.
     */
    public void synchronize(String standardCode, String stockName, Market market, MarketType marketType,
            boolean managed, boolean tradingHalted, Instant syncedAt) {
        this.standardCode = standardCode;
        this.stockName = stockName;
        this.market = market;
        applyMarketType(marketType);
        this.managed = managed;
        this.tradingHalted = tradingHalted;
        this.active = true;
        this.masterSyncedAt = syncedAt;
    }

    /**
     * 상품 유형을 저장하고, 그 유형에 맞게 ETF/ETN 표시를 함께 맞춰요.
     * 받는 것: 상품 유형. 돌려주는 것: 없음.
     * 유형과 is_etf/is_etn 컬럼이 서로 어긋나지 않게 한 곳에서만 정해요.
     */
    private void applyMarketType(MarketType type) {
        this.marketType = type;
        this.etf = type == MarketType.ETF;
        this.etn = type == MarketType.ETN;
    }

    /** 처음 DB에 저장되기 직전에 생성·수정 시각을 현재 시각으로 채워요. */
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** DB 값이 바뀌어 저장되기 직전에 수정 시각을 현재 시각으로 바꿔요. */
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // 아래는 저장된 값을 그대로 읽어 가는 조회용 메서드(getter)예요. 받는 것은 없고 해당 필드 값을 돌려줘요.

    public Long getId() {
        return id;
    }

    public String getStockCode() {
        return stockCode;
    }

    public String getStandardCode() {
        return standardCode;
    }

    public String getStockName() {
        return stockName;
    }

    public Market getMarket() {
        return market;
    }

    public MarketType getMarketType() {
        return marketType;
    }

    public boolean isEtf() {
        return etf;
    }

    public boolean isEtn() {
        return etn;
    }

    public boolean isManaged() {
        return managed;
    }

    public boolean isTradingHalted() {
        return tradingHalted;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getMasterSyncedAt() {
        return masterSyncedAt;
    }
}
