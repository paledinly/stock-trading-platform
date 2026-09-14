package com.sunmo.stockplatform.kis.config;

import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;

public class KisRequestRejectedException extends ApplicationException {
    public KisRequestRejectedException(String message) {
        super(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY, message);
    }
}
