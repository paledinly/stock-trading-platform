/*
 * KIS 접근 토큰을 메모리에 캐시(자주 쓰는 값을 가까이 보관해 두는 것)해 두고, 만료가 가까우면 새로 발급받는 파일이에요.
 * 여러 스레드가 동시에 요청해도 토큰 발급은 한 번만 일어나도록 잠금을 써요.
 * 현재가·분봉·일봉·휴장일 등 KIS REST 클라이언트들이 요청 헤더에 넣을 토큰을 여기서 가져가요.
 */
package com.sunmo.stockplatform.kis.auth;

import com.sunmo.stockplatform.kis.config.KisProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * KIS 토큰을 보관하고 필요할 때만 새로 받아 오는 관리자예요.
 * 사무실 열쇠 보관함 담당자처럼, 열쇠가 아직 쓸 만하면 그대로 내주고 곧 못 쓰게 되면 한 사람만 가서 새 열쇠를 받아 와요.
 */
@Component
public class KisTokenManager {
    private final KisAuthClient authClient;
    private final KisProperties properties;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile KisAccessToken cached;

    /**
     * 운영용 생성자예요. 시스템 시계(UTC)를 써요.
     * 받는 것: 토큰 발급 클라이언트, KIS 설정. 돌려주는 것: 없음(생성자).
     */
    @Autowired
    public KisTokenManager(KisAuthClient authClient, KisProperties properties) {
        this(authClient, properties, Clock.systemUTC());
    }

    /**
     * 시계를 직접 넣을 수 있는 생성자예요. 테스트에서 시간을 고정할 때 쓰는 것으로 보여요.
     * 받는 것: 토큰 발급 클라이언트, KIS 설정, 시계. 돌려주는 것: 없음(생성자).
     */
    KisTokenManager(KisAuthClient authClient, KisProperties properties, Clock clock) {
        this.authClient = authClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 지금 쓸 수 있는 접근 토큰 문자열을 돌려줘요.
     * 받는 것: 없음. 돌려주는 것: 유효한 토큰 문자열(필요하면 새로 발급받은 것).
     */
    public String getAccessToken() {
        // 빠른 길: 잠금 없이 캐시된 토큰이 쓸 만한지 먼저 봐요.
        var current = cached;
        if (isUsable(current)) {
            return current.value();
        }
        // 느린 길: 한 스레드만 들어가 발급하고, 기다리던 스레드는 다시 확인해 중복 발급을 막아요.
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

    /**
     * 토큰이 있고, "지금 + 갱신 여유 시간(tokenRefreshSkew)" 이후까지 살아 있는지 확인해요.
     * 받는 것: 확인할 토큰(null 가능). 돌려주는 것: 그대로 써도 되면 true.
     */
    private boolean isUsable(KisAccessToken token) {
        return token != null && !token.expiresBefore(clock.instant().plus(properties.tokenRefreshSkew()));
    }
}
