package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

public interface ClosingRecommendationRunRepository extends JpaRepository<ClosingRecommendationRun, Long> {
    Optional<ClosingRecommendationRun> findFirstByRecommendationDateOrderByIdDesc(LocalDate date);
    Optional<ClosingRecommendationRun> findByRequestKey(String key);
    List<ClosingRecommendationRun> findByRecommendationDateOrderByIdDesc(LocalDate date);
}
