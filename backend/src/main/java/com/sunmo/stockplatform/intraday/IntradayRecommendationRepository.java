package com.sunmo.stockplatform.intraday;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.*;

public interface IntradayRecommendationRepository extends JpaRepository<IntradayRecommendationEntity, UUID> {
    List<IntradayRecommendationEntity> findBySessionDateOrderByRecommendedAtAsc(LocalDate date);
    List<IntradayRecommendationEntity> findByTrackingCompleteFalse();
}
