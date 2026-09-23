package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ClosingRecommendationRepository extends JpaRepository<ClosingRecommendation, Long> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ClosingRecommendation r where r.recommendationDate = :date")
    void deleteByRecommendationDate(@Param("date") LocalDate recommendationDate);

    @Query(value = """
            select r.* from closing_recommendation r
             where r.run_id = coalesce(
                 (select max(a.id) from closing_recommendation_run a
                   where a.recommendation_date = :date and a.execution_mode = 'FORWARD'),
                 (select max(a.id) from closing_recommendation_run a
                   where a.recommendation_date = :date))
             order by r.rank_no asc
            """, nativeQuery = true)
    List<ClosingRecommendation> findByRecommendationDateOrderByRankAsc(@Param("date") LocalDate recommendationDate);

    List<ClosingRecommendation> findByRunIdOrderByRankAsc(Long runId);

    @Query("""
            select stock.stockCode from ClosingRecommendation recommendation
              join recommendation.stock stock
             where recommendation.run.id = :runId
             order by recommendation.rank asc
            """)
    List<String> findStockCodesByRunId(@Param("runId") Long runId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ClosingRecommendation r where r.id = :id")
    java.util.Optional<ClosingRecommendation> findLockedById(@Param("id") Long id);
}
