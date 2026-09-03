package com.sunmo.stockplatform.trade.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "investment_journal")
public class InvestmentJournal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trade_id", unique = true)
    private Trade trade;
    @Column(columnDefinition = "text")
    private String memo;
    @Column(name = "target_price", precision = 20, scale = 4)
    private BigDecimal targetPrice;
    @Column(name = "stop_loss_price", precision = 20, scale = 4)
    private BigDecimal stopLossPrice;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trade_reason", joinColumns = @JoinColumn(name = "trade_id", referencedColumnName = "trade_id"))
    @AttributeOverrides({ @AttributeOverride(name = "code", column = @Column(name = "reason_code")),
            @AttributeOverride(name = "customReason", column = @Column(name = "custom_reason")) })
    private Set<TradeReason> reasons = new LinkedHashSet<>();
    @Version
    private long version;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvestmentJournal() {
    }

    public InvestmentJournal(Trade trade) {
        this.trade = trade;
    }

    public void update(String memo, BigDecimal target, BigDecimal stop, Set<TradeReason> reasons) {
        this.memo = memo;
        this.targetPrice = target;
        this.stopLossPrice = stop;
        this.reasons.clear();
        this.reasons.addAll(reasons);
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void create() {
        updatedAt = Instant.now();
    }

    public String getMemo() {
        return memo;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }

    public Set<TradeReason> getReasons() {
        return Collections.unmodifiableSet(reasons);
    }

    public long getVersion() {
        return version;
    }
}
