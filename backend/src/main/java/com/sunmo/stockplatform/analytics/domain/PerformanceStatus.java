/*
 * 감지 성과 기록이 지금 어떤 단계인지 나타내는 상태 값을 정의한 파일이에요.
 * DetectionPerformance, 추적기, 통계 서비스가 이 상태로 기록을 구분해요.
 */
package com.sunmo.stockplatform.analytics.domain;

/**
 * 성과 기록의 진행 상태예요. 택배 배송 조회의 "배송 중 / 배송 완료 / 일부 분실" 표시와 비슷해요.
 * PENDING: 아직 추적 중이에요. COMPLETED: 5·10·30·60분과 마감 가격이 모두 기록된 채 확정됐어요.
 * DATA_MISSING: 확정됐지만 시점 가격 중 하나 이상이 빠졌어요.
 */
public enum PerformanceStatus {
    PENDING, COMPLETED, DATA_MISSING
}
