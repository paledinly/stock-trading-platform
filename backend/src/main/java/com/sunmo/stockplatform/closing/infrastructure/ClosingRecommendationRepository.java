package com.sunmo.stockplatform.closing.infrastructure;

import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ClosingRecommendationRepository extends JpaRepository<ClosingRecommendation, Long> {
    void deleteByRecommendationDate(LocalDate recommendationDate);

    List<ClosingRecommendation> findByRecommendationDateOrderByRankAsc(LocalDate recommendationDate);
}
