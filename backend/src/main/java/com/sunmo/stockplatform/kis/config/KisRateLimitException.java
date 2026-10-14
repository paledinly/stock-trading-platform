/*
 * KIS가 요청이 너무 많다고 거절했을 때 쓰는 전용 예외 파일이에요.
 * HTTP 429(요청 과다) 상태로 응답되도록 공통 예외를 확장해요.
 * KisResponseErrors가 던지고, KisRequestExecutor가 잡아서 잠시 쉬었다 재시도해요.
 */
package com.sunmo.stockplatform.kis.config;

import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;

/**
 * KIS rate limit(일정 시간당 호출 횟수 제한)에 걸렸다는 뜻의 예외예요.
 * 놀이기구 앞 "잠시 후 다시 오세요" 팻말처럼, 고장이 아니라 속도를 늦추라는 신호로 쓰여요.
 */
public class KisRateLimitException extends ApplicationException {
    /**
     * 받는 것: 오류 설명 문구. 돌려주는 것: 없음(생성자). KIS_API_ERROR 코드와 HTTP 429로 고정돼요.
     */
    public KisRateLimitException(String message) {
        super(ErrorCode.KIS_API_ERROR, HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
