/*
 * KIS에서 받은 접근 토큰 값과 만료 시각을 한 묶음으로 담는 파일이에요.
 * 특정 시각 전에 만료되는지 확인하는 작은 도우미 메서드도 있어요.
 * KisAuthClient가 만들고 KisTokenManager가 캐시해 두고 써요.
 */
package com.sunmo.stockplatform.kis.auth;

import java.time.Instant;

/**
 * KIS 접근 토큰(API 호출용 임시 출입증)과 그 만료 시각을 담는 값 객체예요.
 * 놀이공원 손목밴드에 "오늘 18시까지"라고 적힌 것처럼, 출입증과 유효기간을 함께 들고 다녀요.
 * value()는 토큰 문자열, expiresAt()은 만료 시각을 돌려줘요.
 */
public record KisAccessToken(String value, Instant expiresAt) {
    /**
     * 주어진 기준 시각까지(같은 시각 포함) 토큰이 만료되는지 확인해요.
     * 받는 것: 기준 시각. 돌려주는 것: 그때 이미 만료됐거나 딱 만료되면 true.
     */
    public boolean expiresBefore(Instant threshold) {
        return expiresAt.isBefore(threshold) || expiresAt.equals(threshold);
    }
}
