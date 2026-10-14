/*
 * 설정의 kis.requests.* 값(요청 간격, 현재가 간격, 한도 초과 시 쉬는 시간, 최대 재시도 횟수)을 담는 파일이에요.
 * 값이 없으면 기본값을 넣고, 위험할 만큼 크거나 음수인 값은 앱 시작 때 거부해요.
 * KisRequestExecutor가 이 값으로 KIS 호출 속도를 조절해요.
 */
package com.sunmo.stockplatform.kis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * KIS 호출 속도 조절 설정이에요.
 * 신호등 주기표처럼, 차(요청)를 얼마 간격으로 보낼지와 막혔을 때 얼마나 기다릴지를 적어 둔 표예요.
 * minimumInterval(모든 요청 사이 최소 간격, 기본 125ms), quoteInterval(현재가 요청 사이 간격, 기본 250ms),
 * rateLimitBackoff(한도 초과 시 기본 쉬는 시간, 기본 500ms), maxRetries(재시도 최대 횟수, 0~2)를 담아요.
 */
@ConfigurationProperties(prefix = "kis.requests")
public record KisRequestProperties(Duration minimumInterval, Duration quoteInterval, Duration rateLimitBackoff,
        int maxRetries) {
    /**
     * 빈 값에 기본값을 채우고 허용 범위를 검사하는 생성자예요.
     * 받는 것: 설정에서 읽은 네 값. 돌려주는 것: 없음, 범위를 벗어나면 IllegalArgumentException을 던져요.
     */
    public KisRequestProperties {
        minimumInterval = minimumInterval == null ? Duration.ofMillis(125) : minimumInterval;
        quoteInterval = quoteInterval == null ? Duration.ofMillis(250) : quoteInterval;
        rateLimitBackoff = rateLimitBackoff == null ? Duration.ofMillis(500) : rateLimitBackoff;
        // 간격은 0~2초, 쉬는 시간은 0~5초, 재시도는 0~2회만 허용해요.
        if (minimumInterval.isNegative() || quoteInterval.isNegative() || rateLimitBackoff.isNegative()
                || minimumInterval.compareTo(Duration.ofSeconds(2)) > 0 || quoteInterval.compareTo(Duration.ofSeconds(2)) > 0
                || rateLimitBackoff.compareTo(Duration.ofSeconds(5)) > 0 || maxRetries < 0 || maxRetries > 2)
            throw new IllegalArgumentException("Invalid KIS pacing/retry configuration");
    }
}
