/*
 * BROAD 스냅샷(MarketBroadSnapshot)을 DB에서 읽고 쓰는 레포지토리(DB 창고 담당자)예요.
 * 커버리지용 종목별 최신 기록, 회차·종목으로 한 건 찾기, 종가 추천용 시간 구간 조회 등을 제공해요.
 * BroadSnapshotService, MarketCoverageService, ClosingRecommendationService가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

/**
 * BROAD 스냅샷을 보관하고 조건에 맞게 꺼내 주는 창구예요.
 * 회차별 출석부 묶음에서 "학생별 가장 최근 기록"이나 "특정 시간대 기록"을 찾아 주는 직원과 같아요.
 */
public interface MarketBroadSnapshotRepository extends JpaRepository<MarketBroadSnapshot, Long> {
    /**
     * 하는 일: 거래일에 종목마다 가장 최근 스냅샷 하나만 골라 커버리지에 필요한 칸만 읽어요.
     * 더 늦은 수집 시각(같으면 더 큰 id)의 기록이 없는 줄만 남기는 방식이에요.
     * 받는 것: 거래일. 돌려주는 것: 종목별 최신 커버리지 행 목록.
     */
    @Query("""
            select s.stock.id as stockId, s.dataQuality as dataQuality, s.collectionStatus as collectionStatus,
                   st.active as active, st.managed as managed, st.tradingHalted as tradingHalted,
                   st.etf as etf, st.etn as etn
              from MarketBroadSnapshot s join s.stock st
             where s.sessionDate = :date and not exists (
                 select newer.id from MarketBroadSnapshot newer
                  where newer.sessionDate = s.sessionDate and newer.stock.id = s.stock.id
                    and (newer.capturedAt > s.capturedAt or (newer.capturedAt = s.capturedAt and newer.id > s.id)))
            """)
    List<BroadCoverageRow> findLatestCoverage(@Param("date") LocalDate date);

    /**
     * 하는 일: 거래일·수집 시각·종목이 모두 같은 스냅샷 한 건을 찾아요(같은 회차 재수집 시 덮어쓰려고 써요).
     * 받는 것: 거래일, 수집 시각, 종목 id. 돌려주는 것: 찾은 스냅샷(없으면 빈 Optional).
     */
    Optional<MarketBroadSnapshot> findBySessionDateAndCapturedAtAndStockId(
            LocalDate sessionDate, Instant capturedAt, Long stockId);

    /**
     * 하는 일: 거래일의 스냅샷 이력을 최신 수집 시각·높은 점수 순으로 가져와요. 종목 코드를 주면 그 종목만 봐요.
     * 받는 것: 거래일, 종목 코드(null이면 전체), 페이지 정보(몇 건씩 볼지).
     * 돌려주는 것: 스냅샷 목록(종목 정보도 함께 읽어 와요).
     * 현재 main 코드에서는 호출하는 곳이 보이지 않아요(테스트 등에서 쓰는 것으로 보여요).
     */
    @Query("""
            select snapshot from MarketBroadSnapshot snapshot
             join fetch snapshot.stock stock
             where snapshot.sessionDate = :date
               and (:stockCode is null or stock.stockCode = :stockCode)
             order by snapshot.capturedAt desc, snapshot.broadScore desc, snapshot.id desc
            """)
    java.util.List<MarketBroadSnapshot> findHistory(@Param("date") LocalDate date,
            @Param("stockCode") String stockCode, Pageable pageable);

    /**
     * 하는 일: 거래일의 [from, to] 시간 구간에 수집된 스냅샷을 최신·높은 점수 순으로 가져와요.
     * 종가 추천에서 평가 시각 이전에 실제로 볼 수 있었던 기록만 고르려고 써요.
     * 받는 것: 거래일, 구간 시작, 구간 끝(둘 다 포함). 돌려주는 것: 스냅샷 목록.
     */
    @Query("""
            select snapshot from MarketBroadSnapshot snapshot
             join fetch snapshot.stock stock
             where snapshot.sessionDate = :date
               and snapshot.capturedAt >= :from
               and snapshot.capturedAt <= :to
             order by snapshot.capturedAt desc, snapshot.broadScore desc, snapshot.id desc
            """)
    List<MarketBroadSnapshot> findClosingCandidates(@Param("date") LocalDate date,
            @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 하는 일: 거래일의 모든 스냅샷을 수집 시각 오름차순으로 가져와요.
     * 받는 것: 거래일. 돌려주는 것: 스냅샷 목록.
     * 현재 main 코드에서는 호출하는 곳이 보이지 않아요(테스트 등에서 쓰는 것으로 보여요).
     */
    List<MarketBroadSnapshot> findBySessionDateOrderByCapturedAtAsc(LocalDate date);
}
