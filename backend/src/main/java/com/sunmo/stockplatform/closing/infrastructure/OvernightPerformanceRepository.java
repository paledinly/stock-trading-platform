package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.*;

public interface OvernightPerformanceRepository extends JpaRepository<OvernightPerformance, Long> {
    Optional<OvernightPerformance> findByRecommendationId(Long recommendationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from OvernightPerformance p
             where p.recommendation.id in (
                 select r.id from ClosingRecommendation r
                  where r.recommendationDate = :date
             )
            """)
    void deleteByRecommendationDate(@Param("date") LocalDate date);

    @Query("""
            select p from OvernightPerformance p
              join fetch p.recommendation r
              join fetch r.stock
             where r.run.id = (select max(a.id) from ClosingRecommendationRun a where a.recommendationDate = :date)
             order by r.rank asc
            """)
    List<OvernightPerformance> findByRecommendationDate(@Param("date") LocalDate date);

    List<OvernightPerformance> findByRecommendationRunIdOrderByRecommendationRankAsc(Long runId);

    @Query("""
            select p from OvernightPerformance p
              join fetch p.recommendation r
              join fetch r.run run
              join fetch r.stock
             where p.status = com.sunmo.stockplatform.closing.domain.OvernightPerformanceStatus.COMPLETED
               and p.calculationVersion = :version
               and r.recommendationDate between :from and :to
               and run.executionMode = 'FORWARD'
               and run.requestKey like 'official-%'
             order by r.recommendationDate asc, r.rank asc
            """)
    List<OvernightPerformance> findOfficialCompletedBetween(@Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("version") String version);
}
