/*
 * 이 프로젝트에서 "예상 가능한 실패"를 표현하는 공통 예외 클래스예요.
 * 오류 종류(ErrorCode)와 HTTP 응답 상태(예: 404, 502)를 함께 담아 던져요.
 * 서비스와 KIS 클라이언트 곳곳에서 던지고, GlobalExceptionHandler가 받아서 응답으로 바꿔요.
 */
package com.sunmo.stockplatform.common.error;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드와 HTTP 상태를 함께 실어 나르는 실행 중 예외예요.
 * 택배 상자에 "파손 주의, 반송 사유: 주소 불명" 같은 꼬리표를 붙여 보내는 것처럼,
 * 무엇이 잘못됐는지(errorCode)와 사용자에게 어떤 상태로 알려야 하는지(status)를 예외에 붙여 보내요.
 */
public class ApplicationException extends RuntimeException {
    private final ErrorCode errorCode;
    private final HttpStatus status;

    /**
     * 원인 예외 없이 예외를 만들어요.
     * 받는 것: 오류 코드, HTTP 상태, 사람이 읽을 메시지. 돌려주는 것: 새 예외 객체.
     */
    public ApplicationException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    /**
     * 원래 원인이 된 예외(cause)까지 함께 담아 예외를 만들어요.
     * 받는 것: 오류 코드, HTTP 상태, 메시지, 원인 예외. 돌려주는 것: 새 예외 객체.
     */
    public ApplicationException(ErrorCode errorCode, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
    }

    /** 담긴 오류 코드를 돌려줘요. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 응답에 쓸 HTTP 상태를 돌려줘요. */
    public HttpStatus status() {
        return status;
    }
}
