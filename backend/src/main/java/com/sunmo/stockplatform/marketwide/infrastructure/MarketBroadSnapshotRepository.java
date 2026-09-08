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
