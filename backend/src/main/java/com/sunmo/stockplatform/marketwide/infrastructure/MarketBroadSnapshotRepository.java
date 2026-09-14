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

public interface MarketBroadSnapshotRepository extends JpaRepository<MarketBroadSnapshot, Long> {
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

    Optional<MarketBroadSnapshot> findBySessionDateAndCapturedAtAndStockId(
            LocalDate sessionDate, Instant capturedAt, Long stockId);

    @Query("""
            select snapshot from MarketBroadSnapshot snapshot
             join fetch snapshot.stock stock
             where snapshot.sessionDate = :date
               and (:stockCode is null or stock.stockCode = :stockCode)
             order by snapshot.capturedAt desc, snapshot.broadScore desc, snapshot.id desc
            """)
    java.util.List<MarketBroadSnapshot> findHistory(@Param("date") LocalDate date,
            @Param("stockCode") String stockCode, Pageable pageable);

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

    List<MarketBroadSnapshot> findBySessionDateOrderByCapturedAtAsc(LocalDate date);
}
