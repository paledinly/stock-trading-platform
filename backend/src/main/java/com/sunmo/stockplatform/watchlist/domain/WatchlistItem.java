/*
 * 관심종목 그룹 안의 종목 하나(watchlist_item 테이블의 한 줄)를 표현하는 엔티티예요.
 * 어느 그룹에 어떤 종목이 몇 번째로 있는지, 실시간 고정이 켜져 있는지를 담아요.
 * WatchlistService, InitialWatchlistBootstrap, WatchlistResponse가 사용해요.
 */
package com.sunmo.stockplatform.watchlist.domain;

import com.sunmo.stockplatform.stock.domain.Stock;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 관심종목 항목 엔티티(DB 테이블 한 줄과 1:1로 연결된 자바 객체)예요.
 * 서랍(그룹) 안에 넣어 둔 물건(종목) 하나에 "몇 번째 칸", "항상 켜 두기(실시간 고정)" 스티커를 붙인 것과 같아요.
 * 그룹과 종목은 지연 로딩(LAZY, 실제로 꺼내 쓸 때 DB에서 읽는 방식)으로 연결돼요.
 */
@Entity
@Table(name = "watchlist_item")
public class WatchlistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private WatchlistGroup group;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id")
    private Stock stock;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "realtime_pinned", nullable = false)
    private boolean realtimePinned;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA가 DB에서 읽어 올 때 쓰는 빈 생성자예요. 코드에서 직접 쓰지 않아요. */
    protected WatchlistItem() {
    }

    /** 새 항목을 만들어요(실시간 고정은 꺼진 상태). 받는 것: 그룹, 종목, 표시 순서. 돌려주는 것: 저장 전 항목. */
    public WatchlistItem(WatchlistGroup group, Stock stock, int displayOrder) {
        this.group = group;
        this.stock = stock;
        this.displayOrder = displayOrder;
    }

    /** 항목을 다른 그룹·순서로 옮겨요. 받는 것: 대상 그룹, 새 순서. 돌려주는 것: 없음. */
    public void move(WatchlistGroup group, int displayOrder) {
        this.group = group;
        this.displayOrder = displayOrder;
    }

    /** 실시간 고정 여부를 바꿔요. 실제 구독 추가/해제는 WatchlistService가 따로 해요. */
    public void setRealtimePinned(boolean realtimePinned) {
        this.realtimePinned = realtimePinned;
    }

    /** 처음 저장되기 직전에 생성·수정 시각을 현재 시각으로 채워요. */
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** 수정되어 저장되기 직전에 수정 시각을 갱신해요. */
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // 아래는 값을 그대로 돌려주는 조회용 메서드(getter)예요.

    public Long getId() {
        return id;
    }

    public WatchlistGroup getGroup() {
        return group;
    }

    public Stock getStock() {
        return stock;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public long getVersion() {
        return version;
    }

    public boolean isRealtimePinned() {
        return realtimePinned;
    }
}
