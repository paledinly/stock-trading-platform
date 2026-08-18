package com.sunmo.stockplatform.kis.auth;

import com.sunmo.stockplatform.kis.config.KisProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class KisTokenManager {
    private final KisAuthClient authClient;
    private final KisProperties properties;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile KisAccessToken cached;

    @Autowired
    public KisTokenManager(KisAuthClient authClient, KisProperties properties) {
        this(authClient, properties, Clock.systemUTC());
    }

    KisTokenManager(KisAuthClient authClient, KisProperties properties, Clock clock) {
        this.authClient = authClient;
        this.properties = properties;
        this.clock = clock;
    }

    public String getAccessToken() {
        var current = cached;
        if (isUsable(current)) {
            return current.value();
        }
        refreshLock.lock();
        try {
            current = cached;
            if (!isUsable(current)) {
                cached = authClient.issueToken();
            }
            return cached.value();
        } finally {
            refreshLock.unlock();
        }
    }

    public void invalidate() {
        cached = null;
    }

    private boolean isUsable(KisAccessToken token) {
        return token != null && !token.expiresBefore(clock.instant().plus(properties.tokenRefreshSkew()));
    }
}
