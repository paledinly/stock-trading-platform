package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.*;

public interface OvernightPerformanceRepository extends JpaRepository<OvernightPerformance, Long> {
    Optional<OvernightPerformance> findByRecommendationId(Long recommendationId);

    @Query("""
            select p from OvernightPerformance p
              join fetch p.recommendation r
              join fetch r.stock
             where r.recommendationDate = :date
             order by r.rank asc
            """)
    List<OvernightPerformance> findByRecommendationDate(@Param("date") LocalDate date);
}
