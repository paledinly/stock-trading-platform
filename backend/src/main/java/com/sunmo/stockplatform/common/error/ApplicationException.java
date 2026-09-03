package com.sunmo.stockplatform.common.error;

import org.springframework.http.HttpStatus;

public class ApplicationException extends RuntimeException {
    private final ErrorCode errorCode;
    private final HttpStatus status;

    public ApplicationException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public ApplicationException(ErrorCode errorCode, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }
}
