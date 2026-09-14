package com.sunmo.stockplatform.kis.config;

import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;
import java.util.Locale;

public final class KisResponseErrors {
    private KisResponseErrors() { }
    public static void failure(String context, String code, String message) {
        String detail = context + " [" + code + "]: " + message;
        if ("EGW00201".equals(code)) throw new KisRateLimitException(detail);
        if (message != null && message.toUpperCase(Locale.ROOT).contains("ERROR INPUT FIELD"))
            throw new KisRequestRejectedException(detail);
        throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY, detail);
    }
}
