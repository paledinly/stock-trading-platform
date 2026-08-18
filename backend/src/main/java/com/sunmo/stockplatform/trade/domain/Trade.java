package com.sunmo.stockplatform.trade.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "trade")
public class Trade {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="owner_id",nullable=false) private Long ownerId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="stock_id") private Stock stock;
    @Enumerated(EnumType.STRING) @Column(name="trade_type",nullable=false,length=10) private TradeType tradeType;
    @Column(name="traded_at",nullable=false) private Instant tradedAt;
    @Column(nullable=false,precision=20,scale=4) private BigDecimal price;
    @Column(nullable=false) private long quantity;
    @Column(nullable=false,precision=20,scale=4) private BigDecimal amount;
    @Column(name="idempotency_key",length=100) private String idempotencyKey;
    @Version private long version;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected Trade() {}
    public Trade(Long ownerId,Stock stock,TradeType type,Instant tradedAt,BigDecimal price,long quantity,String key){this.ownerId=ownerId;this.stock=stock;update(type,tradedAt,price,quantity);this.idempotencyKey=key;}
    public void update(TradeType type,Instant tradedAt,BigDecimal price,long quantity){this.tradeType=type;this.tradedAt=tradedAt;this.price=price;this.quantity=quantity;this.amount=price.multiply(BigDecimal.valueOf(quantity));}
    @PrePersist void create(){createdAt=Instant.now();updatedAt=createdAt;} @PreUpdate void updateTime(){updatedAt=Instant.now();}
    public Long getId(){return id;} public Long getOwnerId(){return ownerId;} public Stock getStock(){return stock;} public TradeType getTradeType(){return tradeType;} public Instant getTradedAt(){return tradedAt;} public BigDecimal getPrice(){return price;} public long getQuantity(){return quantity;} public BigDecimal getAmount(){return amount;} public long getVersion(){return version;}
}
