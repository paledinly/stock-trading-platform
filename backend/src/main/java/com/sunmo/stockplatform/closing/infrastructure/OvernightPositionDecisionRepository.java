package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.OvernightPositionDecision;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OvernightPositionDecisionRepository extends JpaRepository<OvernightPositionDecision, Long> {
    @Query("""
            select d from OvernightPositionDecision d
            join d.recommendation recommendation
            where recommendation.recommendationDate = :date
              and d.evaluatedAt = (
                  select max(latest.evaluatedAt) from OvernightPositionDecision latest
                  where latest.recommendation = recommendation
              )
            order by recommendation.rank asc
            """)
    List<OvernightPositionDecision> findLatestByRecommendationDate(@Param("date") LocalDate date);
}
