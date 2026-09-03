package com.sunmo.stockplatform.stock.domain;

import jakarta.persistence.*;

import java.time.Instant;

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

    protected Stock() {
    }

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

    private void applyMarketType(MarketType type) {
        this.marketType = type;
        this.etf = type == MarketType.ETF;
        this.etn = type == MarketType.ETN;
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
