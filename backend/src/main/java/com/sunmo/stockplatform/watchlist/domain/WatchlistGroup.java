/*
 * 관심종목 그룹 하나(watchlist_group 테이블의 한 줄)를 표현하는 엔티티예요.
 * 그룹 이름, 표시 순서, 소유자, 동시 수정 확인용 version, 생성/수정 시각을 담아요.
 * WatchlistService, InitialWatchlistBootstrap, WatchlistResponse가 사용해요.
 */
package com.sunmo.stockplatform.watchlist.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 관심종목 그룹 엔티티(DB 테이블 한 줄과 1:1로 연결된 자바 객체)예요.
 * 서랍장의 서랍 하나에 붙은 이름표처럼, 어떤 종목들을 묶어 둘지 이름과 순서를 정해요.
 * {@code @Version}은 낙관적 잠금(저장할 때 "그사이 누가 바꿨나?"를 번호로 확인하는 방식)에 쓰여요.
 */
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

    /** JPA가 DB에서 읽어 올 때 쓰는 빈 생성자예요. 코드에서 직접 쓰지 않아요. */
    protected WatchlistGroup() {
    }

    /** 새 그룹을 만들어요. 받는 것: 소유자 id, 이름, 표시 순서. 돌려주는 것: 아직 저장 전인 그룹. */
    public WatchlistGroup(Long ownerId, String name, int displayOrder) {
        this.ownerId = ownerId;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    /** 이름과 순서 중 null이 아닌 값만 바꿔요. 받는 것: 새 이름(선택), 새 순서(선택). 돌려주는 것: 없음. */
    public void update(String name, Integer displayOrder) {
        if (name != null)
            this.name = name;
        if (displayOrder != null)
            this.displayOrder = displayOrder;
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
