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
}
