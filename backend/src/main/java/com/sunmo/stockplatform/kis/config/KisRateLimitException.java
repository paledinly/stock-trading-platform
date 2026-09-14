package com.sunmo.stockplatform.kis.config;

import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;

public class KisRateLimitException extends ApplicationException {
    public KisRateLimitException(String message) {
        super(ErrorCode.KIS_API_ERROR, HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
