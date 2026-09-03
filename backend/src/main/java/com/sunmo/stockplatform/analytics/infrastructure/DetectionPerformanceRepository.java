package com.sunmo.stockplatform.analytics.infrastructure;

import com.sunmo.stockplatform.analytics.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface DetectionPerformanceRepository extends JpaRepository<DetectionPerformance, Long> {
    @Query("select p from DetectionPerformance p join fetch p.detection d join fetch d.stock where p.status = :status")
    List<DetectionPerformance> findWithDetectionByStatus(@Param("status") PerformanceStatus status);
}
