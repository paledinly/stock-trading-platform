/*
 * KIS가 요청 입력값 자체를 잘못됐다고 거절했을 때(예: ERROR INPUT FIELD) 쓰는 전용 예외 파일이에요.
 * 재시도해도 소용없는 오류를 속도 제한 오류와 구분하려고 따로 둔 것으로 보여요.
 * KisResponseErrors가 응답 메시지를 보고 이 예외를 던져요.
 */
package com.sunmo.stockplatform.kis.config;

import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;

/**
 * KIS가 입력값 문제로 요청을 거절했다는 뜻의 예외예요.
 * 우체국에서 주소 형식이 틀려 접수 불가로 돌려받은 편지처럼, 그대로 다시 보내도 같은 결과가 나와요.
 */
public class KisRequestRejectedException extends ApplicationException {
    /**
     * 받는 것: 오류 설명 문구. 돌려주는 것: 없음(생성자). KIS_API_ERROR 코드와 HTTP 502로 고정돼요.
     */
    public KisRequestRejectedException(String message) {
        super(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY, message);
    }
}
