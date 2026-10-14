/*
 * 장마감 추천 한 줄의 상태 값 목록(enum)이에요.
 * ClosingRecommendation 엔티티의 status 칸에 문자열로 저장돼요.
 */
package com.sunmo.stockplatform.closing.domain;

/**
 * 추천 상태예요. CANDIDATE(후보로 생성됨), SELECTED(선정됨), EXCLUDED(제외됨)를 뜻하는 것으로 보여요.
 * 오디션 명단의 "지원/합격/탈락" 도장처럼 한 줄에 하나만 찍혀요.
 * (현재 엔티티 생성자는 CANDIDATE만 설정하고, 다른 값으로 바꾸는 메서드는 이 패키지에 없어요.)
 */
public enum ClosingRecommendationStatus {
    CANDIDATE,
    SELECTED,
    EXCLUDED
}
