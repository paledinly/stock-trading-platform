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

    @Query("""
            select r from ClosingRecommendation r where r.run.id =
              (select max(a.id) from ClosingRecommendationRun a where a.recommendationDate = :date)
            order by r.rank asc
            """)
    List<ClosingRecommendation> findByRecommendationDateOrderByRankAsc(@Param("date") LocalDate recommendationDate);

    List<ClosingRecommendation> findByRunIdOrderByRankAsc(Long runId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ClosingRecommendation r where r.id = :id")
    java.util.Optional<ClosingRecommendation> findLockedById(@Param("id") Long id);
}
