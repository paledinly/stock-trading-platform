package com.sunmo.stockplatform.kis.auth;

import java.time.Instant;

public record KisAccessToken(String value, Instant expiresAt) {
    public boolean expiresBefore(Instant threshold) {
        return expiresAt.isBefore(threshold) || expiresAt.equals(threshold);
    }
}

