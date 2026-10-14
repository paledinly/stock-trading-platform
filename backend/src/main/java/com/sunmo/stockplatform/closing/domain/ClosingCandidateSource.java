/*
 * 장마감 후보가 어느 경로에서 왔는지 구분하는 값 목록(enum)이에요.
 * 추천·후보 관찰 기록과 시장 커버리지 집계가 출처별로 나눠 셀 때 사용해요.
 */
package com.sunmo.stockplatform.closing.domain;

/**
 * 후보 출처예요. PRECISION은 실시간 구독으로 정밀 관측한 종목, BROAD는 시장 전체 순위 수집(Broad)에서 온 종목이에요.
 * 같은 과일이라도 "직접 키운 것"과 "시장에서 산 것"을 꼬리표로 구분해 두는 것과 같아요.
 */
public enum ClosingCandidateSource {
    PRECISION,
    BROAD
}
