package com.sunmo.stockplatform.watchlist.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watchlist_group")
public class WatchlistGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(nullable = false, length = 80)
    private String name;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WatchlistGroup() {
    }

    public WatchlistGroup(Long ownerId, String name, int displayOrder) {
        this.ownerId = ownerId;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public void update(String name, Integer displayOrder) {
        if (name != null)
            this.name = name;
        if (displayOrder != null)
            this.displayOrder = displayOrder;
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

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public long getVersion() {
        return version;
    }
}
