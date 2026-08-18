package com.sunmo.stockplatform.watchlist.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watchlist_item")
public class WatchlistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "group_id") private WatchlistGroup group;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "stock_id") private Stock stock;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Version private long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected WatchlistItem() {}
    public WatchlistItem(WatchlistGroup group, Stock stock, int displayOrder) { this.group = group; this.stock = stock; this.displayOrder = displayOrder; }
    public void move(WatchlistGroup group, int displayOrder) { this.group = group; this.displayOrder = displayOrder; }
    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public WatchlistGroup getGroup() { return group; }
    public Stock getStock() { return stock; }
    public int getDisplayOrder() { return displayOrder; }
    public long getVersion() { return version; }
}
