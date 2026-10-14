/*
 * 거래 한 건에 붙는 투자 노트(메모, 목표가, 손절가, 매매 이유 목록)를 DB investment_journal 테이블과 연결하는 파일이에요.
 * 매매 이유는 별도 trade_reason 테이블에 거래 id 기준으로 저장돼요.
 * TradeService가 노트를 만들고 고치며, TradeResponse가 화면용으로 옮겨 담아요.
 */
package com.sunmo.stockplatform.trade.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * 거래 하나에 딱 하나 붙는 투자 노트 엔티티(DB 표의 한 줄과 연결된 객체)예요.
 * 영수증 뒷면에 "왜 샀는지, 얼마에 팔 생각인지, 얼마면 손절할지"를 적어 두는 메모와 같아요.
 * version 필드로 동시에 두 곳에서 고칠 때 나중 것이 앞의 수정을 덮어쓰지 않게 막아요.
 */
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
    // 매매 이유 목록은 trade_reason 테이블에 노트의 trade_id 기준으로 저장되고, 노트를 읽을 때 함께 읽어 와요(EAGER).
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trade_reason", joinColumns = @JoinColumn(name = "trade_id", referencedColumnName = "trade_id"))
    @AttributeOverrides({ @AttributeOverride(name = "code", column = @Column(name = "reason_code")),
            @AttributeOverride(name = "customReason", column = @Column(name = "custom_reason")) })
    private Set<TradeReason> reasons = new LinkedHashSet<>();
    @Version
    private long version;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA가 DB에서 읽은 값을 채울 때 쓰는 빈 생성자예요. */
    protected InvestmentJournal() {
    }

    /**
     * 거래에 붙일 빈 노트를 만들어요.
     * 받는 것: 거래. 돌려주는 것: 내용이 비어 있는 새 노트.
     */
    public InvestmentJournal(Trade trade) {
        this.trade = trade;
    }

    /**
     * 노트 내용을 통째로 바꿔요(이유 목록도 전부 갈아 끼워요).
     * 받는 것: 메모, 목표가, 손절가, 이유 목록. 돌려주는 것: 없음. 수정 시각도 갱신해요.
     */
    public void update(String memo, BigDecimal target, BigDecimal stop, Set<TradeReason> reasons) {
        this.memo = memo;
        this.targetPrice = target;
        this.stopLossPrice = stop;
        this.reasons.clear();
        this.reasons.addAll(reasons);
        this.updatedAt = Instant.now();
    }

    /** 처음 DB에 저장되기 직전에 수정 시각을 채워요. */
    @PrePersist
    void create() {
        updatedAt = Instant.now();
    }

    // 아래는 저장된 값을 읽어 가는 조회용 메서드(getter)예요. 이유 목록은 밖에서 고칠 수 없는 읽기 전용으로 돌려줘요.

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
