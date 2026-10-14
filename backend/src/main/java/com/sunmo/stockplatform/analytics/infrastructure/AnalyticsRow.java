/*
 * 통계 계산에 필요한 칸만 골라 담은 조회 결과 한 줄의 모양을 정의한 파일이에요.
 * 성과 수익률과 감지 정보(감지 시각, 스캐너 종류, 기회·위험 점수)를 함께 담아요.
 * DetectionPerformanceRepository가 이 모양으로 결과를 돌려주고 ScannerAnalyticsService가 읽어요.
 */
package com.sunmo.stockplatform.analytics.infrastructure;
import java.math.BigDecimal;
import java.time.Instant;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
/**
 * 통계용 조회 결과 한 줄(프로젝션: 테이블의 일부 칸만 뽑아 낸 모양)이에요.
 * 두꺼운 학생 기록부 대신 성적 계산에 필요한 칸만 옮겨 적은 요약 카드라고 생각하면 돼요.
 * 각 메서드는 이름 그대로의 값(상태, 계산 버전, 수익률 %, 감지 시각, 스캐너 종류, 점수)을 돌려줘요.
 */
public interface AnalyticsRow {
    PerformanceStatus getStatus();
    String getCalculationVersion();
    BigDecimal getReturn5m();
    BigDecimal getReturn10m();
    BigDecimal getReturn30m();
    BigDecimal getReturn60m();
    BigDecimal getReturnClose();
    BigDecimal getMaxReturn();
    BigDecimal getMaxDrawdown();
    BigDecimal getMfe();
    BigDecimal getMae();
    Instant getDetectedAt();
    ScannerType getType();
    BigDecimal getOpportunityScore();
    BigDecimal getRiskScore();
}
