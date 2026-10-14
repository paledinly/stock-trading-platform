/*
 * KIS 접근 토큰을 발급받는 방법을 약속(인터페이스)으로 정해 둔 파일이에요.
 * 실제 HTTP 구현은 HttpKisAuthClient에 있고, 테스트에서는 가짜 구현으로 바꿔 끼울 수 있어요.
 * KisTokenManager가 이 인터페이스를 통해 토큰을 받아요.
 */
package com.sunmo.stockplatform.kis.auth;

/**
 * "토큰 한 장 발급해 주세요"라는 창구 역할만 정해 둔 인터페이스예요.
 * 매표소 창구 모양만 정해 두고, 실제 매표 직원은 나중에 앉히는 것과 같아요.
 */
public interface KisAuthClient {
    /**
     * KIS에서 새 접근 토큰을 발급받아요.
     * 받는 것: 없음. 돌려주는 것: 토큰 값과 만료 시각.
     */
    KisAccessToken issueToken();
}
