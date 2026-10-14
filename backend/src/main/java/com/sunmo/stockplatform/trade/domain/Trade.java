/*
 * 사용자가 직접 기록한 매수/매도 거래 한 건을 DB trade 테이블과 연결하는 파일이에요.
 * 가격 × 수량으로 거래 금액을 계산해 함께 저장해요. 실제 주문이 아니라 수동 기록이에요.
 * TradeService, PortfolioCalculator, TradeResponse가 사용해요.
 */
package com.sunmo.stockplatform.trade.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 거래 기록 한 건을 나타내는 엔티티(DB 표의 한 줄과 연결된 객체)예요.
 * 가계부의 한 줄처럼 "언제, 어떤 종목을, 얼마에, 몇 주, 샀는지/팔았는지"를 적어 두는 칸이에요.
 * version 필드로 동시에 고칠 때 앞의 수정을 덮어쓰지 않게 막아요.
 */
@Entity
@Table(name = "trade")
public class Trade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id")
    private Stock stock;
    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false, length = 10)
    private TradeType tradeType;
    @Column(name = "traded_at", nullable = false)
    private Instant tradedAt;
    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal price;
    @Column(nullable = false)
    private long quantity;
    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA가 DB에서 읽은 값을 채울 때 쓰는 빈 생성자예요. */
    protected Trade() {
    }

    /**
     * 새 거래 기록을 만들어요.
     * 받는 것: 사용자 id, 종목, 매수/매도, 거래 시각, 가격, 수량, 중복 방지 키(없으면 null).
     * 돌려주는 것: 금액까지 계산된 새 거래 객체.
     */
    public Trade(Long ownerId, Stock stock, TradeType type, Instant tradedAt, BigDecimal price, long quantity,
            String key) {
        this.ownerId = ownerId;
        this.stock = stock;
        update(type, tradedAt, price, quantity);
        this.idempotencyKey = key;
    }

    /**
     * 거래 내용(종목 제외)을 바꾸고 금액(가격 × 수량)을 다시 계산해요.
     * 받는 것: 매수/매도, 거래 시각, 가격, 수량. 돌려주는 것: 없음.
     */
    public void update(TradeType type, Instant tradedAt, BigDecimal price, long quantity) {
        this.tradeType = type;
        this.tradedAt = tradedAt;
        this.price = price;
        this.quantity = quantity;
        this.amount = price.multiply(BigDecimal.valueOf(quantity));
    }

    /** 처음 DB에 저장되기 직전에 생성·수정 시각을 채워요. */
    @PrePersist
    void create() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** DB 값이 바뀌어 저장되기 직전에 수정 시각을 갱신해요. */
    @PreUpdate
    void updateTime() {
        updatedAt = Instant.now();
    }

    // 아래는 저장된 값을 그대로 읽어 가는 조회용 메서드(getter)예요. 받는 것은 없고 해당 필드 값을 돌려줘요.

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Stock getStock() {
        return stock;
    }

    public TradeType getTradeType() {
        return tradeType;
    }

    public Instant getTradedAt() {
        return tradedAt;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public long getVersion() {
        return version;
    }
}
