package com.sunmo.stockplatform.analytics.infrastructure;

import com.sunmo.stockplatform.analytics.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface DetectionPerformanceRepository extends JpaRepository<DetectionPerformance, Long> {
    @Query("""
            select p.status as status, p.calculationVersion as calculationVersion,
                   p.return5m as return5m, p.return10m as return10m, p.return30m as return30m,
                   p.return60m as return60m, p.returnClose as returnClose, p.maxReturn as maxReturn,
                   p.maxDrawdown as maxDrawdown, p.mfe as mfe, p.mae as mae,
                   d.detectedAt as detectedAt, d.type as type,
                   d.opportunityScore as opportunityScore, d.riskScore as riskScore
              from DetectionPerformance p join p.detection d
             where d.detectedAt >= :from and d.detectedAt <= :to
               and (:settingId is null or d.setting.id = :settingId)
             order by d.detectedAt asc
            """)
    List<AnalyticsRow> findAnalyticsRows(@Param("settingId") Long settingId,
            @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
    @Query("select p from DetectionPerformance p join fetch p.detection d join fetch d.stock where p.status = :status")
    List<DetectionPerformance> findWithDetectionByStatus(@Param("status") PerformanceStatus status);
}
