/*
 * 관심종목 항목을 DB에서 찾고 세고 저장하는 레포지토리(DB 읽기/쓰기를 맡는 창구)예요.
 * 메서드 이름 기반 조회와, 직접 작성한 JPQL(엔티티 기준으로 쓰는 SQL 비슷한 질의) 조회가 섞여 있어요.
 * WatchlistService, 두 Bootstrap, 일봉 보충(DailyCandleBackfillService)이 사용해요.
 */
package com.sunmo.stockplatform.watchlist.infrastructure;

import com.sunmo.stockplatform.watchlist.domain.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * watchlist_item 테이블용 레포지토리예요.
 * 서랍 속 물건 목록을 관리하는 재고 담당자처럼, 항목 찾기·존재 확인·개수 세기·실시간 고정 종목 모으기를 해요.
 * JPA(자바 객체와 DB 테이블을 자동으로 연결해 주는 표준 기술)를 써요.
 */
public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {
    /** 여러 그룹의 항목을 그룹 id → 표시 순서 → id 순으로 한 번에 가져와요. */
    List<WatchlistItem> findByGroupIdInOrderByGroupIdAscDisplayOrderAscIdAsc(List<Long> groupIds);

    /** 항목 id와 그 항목이 속한 그룹의 소유자가 모두 맞을 때만 찾아요. */
    Optional<WatchlistItem> findByIdAndGroupOwnerId(Long id, Long ownerId);

    /** 그 그룹에 그 종목이 이미 들어 있는지 확인해요. */
    boolean existsByGroupIdAndStockId(Long groupId, Long stockId);

    /** 그룹 안 항목 수를 세요. 새 항목의 기본 순서(맨 뒤)를 정할 때 써요. */
    long countByGroupId(Long groupId);

    /** 소유자의 모든 관심종목 코드를 중복 없이 돌려줘요. */
    @org.springframework.data.jpa.repository.Query("select distinct i.stock.stockCode from WatchlistItem i where i.group.ownerId = :ownerId")
    List<String> findDistinctStockCodesByOwnerId(
            @org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    /** 소유자의 실시간 고정 종목 코드를 중복 없이 돌려줘요. 서버 시작 시 구독 복원에 써요. */
    @org.springframework.data.jpa.repository.Query("select distinct i.stock.stockCode from WatchlistItem i where i.group.ownerId = :ownerId and i.realtimePinned = true")
    List<String> findDistinctRealtimePinnedStockCodesByOwnerId(
            @org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    /** 소유자가 실시간 고정한 서로 다른 종목 수를 세요. 고정 한도 확인에 써요. */
    @org.springframework.data.jpa.repository.Query("select count(distinct i.stock.id) from WatchlistItem i where i.group.ownerId = :ownerId and i.realtimePinned = true")
    long countDistinctRealtimePinnedStocksByOwnerId(
            @org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    /** 그 종목이 들어 있는 항목 수를 세요(모든 그룹 합계). */
    long countByStockId(Long stockId);

    /** 그 종목을 실시간 고정한 항목 수를 세요. 0이 되면 구독을 풀어도 된다는 뜻이에요. */
    long countByStockIdAndRealtimePinnedTrue(Long stockId);
}
