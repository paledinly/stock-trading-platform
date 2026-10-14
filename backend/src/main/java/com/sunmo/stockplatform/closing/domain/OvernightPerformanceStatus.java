/*
 * 다음 거래일 성과 계산의 진행 상태 값 목록(enum)이에요.
 * OvernightPerformance와 ClosingCandidateObservation의 status 칸에 저장되고, 재평가 대상을 고를 때도 써요.
 */
package com.sunmo.stockplatform.closing.domain;

/**
 * 성과 계산 상태예요.
 * PENDING: 다음 거래일 장이 아직 안 열림, IN_PROGRESS: 장 중이거나 마감 확정 전, COMPLETED: 필요한 봉이 모두 있어 계산 완료,
 * DATA_MISSING: 진입가나 청산용 봉이 아예 없음, DATA_INCOMPLETE: 봉이 일부 빠져 결과를 확정하지 못함.
 * 택배 조회의 "접수/배송 중/배송 완료/분실/일부 누락" 단계와 비슷해요.
 */
public enum OvernightPerformanceStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    DATA_MISSING,
    DATA_INCOMPLETE
}
