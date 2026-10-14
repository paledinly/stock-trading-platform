/*
 * 감지 성과(detection_performance) 테이블을 읽고 쓰는 레포지토리(DB 읽기·쓰기를 맡는 객체) 파일이에요.
 * 통계용 조회, 상태별 조회, ID 조회를 감지·종목 정보와 함께 한 번에 가져오는 쿼리를 정의해요.
 * DetectionPerformanceTracker와 ScannerAnalyticsService가 써요.
 */
package com.sunmo.stockplatform.analytics.infrastructure;

import com.sunmo.stockplatform.analytics.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

/**
 * 성과 기록을 DB에 넣고 꺼내는 레포지토리예요.
 * 도서관 사서처럼 "이 기간 기록", "추적 중인 기록", "이 번호 기록"을 찾아서 꺼내 줘요.
 */
public interface DetectionPerformanceRepository extends JpaRepository<DetectionPerformance, Long> {
    /**
     * 하는 일: 감지 시각이 기간 안에 있는 성과를 통계용 칸만 골라 시간순으로 가져와요. 상태는 거르지 않아요.
     * 받는 것: 스캐너 설정 ID(null이면 전체), 시작·끝 시각(둘 다 포함). 돌려주는 것: 통계용 행 목록이에요.
     */
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
    /**
     * 하는 일: 주어진 상태의 성과를 감지·종목 정보까지 한 번에 가져와요(서버 시작 시 PENDING 복구에 써요).
     * 받는 것: 상태. 돌려주는 것: 성과 목록이에요.
     */
    @Query("select p from DetectionPerformance p join fetch p.detection d join fetch d.stock where p.status = :status")
    List<DetectionPerformance> findWithDetectionByStatus(@Param("status") PerformanceStatus status);
    /**
     * 하는 일: 감지 ID로 성과 하나를 감지·종목 정보까지 함께 가져와요(저장 충돌 뒤 다시 읽을 때 써요).
     * 받는 것: 감지 ID. 돌려주는 것: 성과, 없으면 빈 값이에요.
     */
    @Query("select p from DetectionPerformance p join fetch p.detection d join fetch d.stock where p.detectionId = :id")
    Optional<DetectionPerformance> findWithDetectionById(@Param("id") Long id);
}
