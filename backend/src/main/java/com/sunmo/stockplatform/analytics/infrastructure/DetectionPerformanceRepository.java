package com.sunmo.stockplatform.analytics.infrastructure;
import com.sunmo.stockplatform.analytics.domain.*;import org.springframework.data.jpa.repository.JpaRepository;import java.util.*;
public interface DetectionPerformanceRepository extends JpaRepository<DetectionPerformance,Long>{List<DetectionPerformance> findByDetectionStockStockCodeAndStatus(String stockCode,PerformanceStatus status);List<DetectionPerformance> findByStatus(PerformanceStatus status);}
