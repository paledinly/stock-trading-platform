/*
 * KIS 응답의 오류 코드와 메시지를 보고 알맞은 예외 종류로 바꿔 던지는 도우미 파일이에요.
 * 호출 한도 초과(EGW00201), 입력값 거절, 그 밖의 KIS 오류를 서로 구분해요.
 * 휴장일, 분봉, 일봉, 현재가 등 KIS REST 클라이언트들이 rt_cd가 0이 아닐 때 호출해요.
 */
package com.sunmo.stockplatform.kis.config;

import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;
import java.util.Locale;

/**
 * KIS 실패 응답을 분류하는 정적 도우미 클래스예요(객체를 만들지 않고 바로 써요).
 * 병원 접수처에서 증상을 듣고 응급실, 일반 진료, 다른 병원 중 어디로 보낼지 정해 주는 분류 담당과 같아요.
 */
public final class KisResponseErrors {
    /** 객체 생성을 막는 빈 생성자예요. */
    private KisResponseErrors() { }
    /**
     * KIS 오류 응답을 예외로 바꿔 항상 던져요(정상 반환하지 않아요).
     * 받는 것: 어떤 요청이었는지 설명, KIS 메시지 코드(msg_cd), 메시지(msg1).
     * 결과: 호출 한도 초과면 KisRateLimitException, 입력값 거절이면 KisRequestRejectedException, 그 외는 일반 KIS 오류.
     */
    public static void failure(String context, String code, String message) {
        String detail = context + " [" + code + "]: " + message;
        if ("EGW00201".equals(code)) throw new KisRateLimitException(detail);
        if (message != null && message.toUpperCase(Locale.ROOT).contains("ERROR INPUT FIELD"))
            throw new KisRequestRejectedException(detail);
        throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY, detail);
    }
}
