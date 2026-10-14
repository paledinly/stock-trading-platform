/*
 * application 설정의 kis.* 값(사용 여부, 주소, 앱 키, 제한시간, 마스터 파일 설정)을 담는 파일이에요.
 * 인증정보가 없을 때 호출을 막는 확인 메서드도 있어요.
 * KIS 관련 거의 모든 클라이언트와 KisTokenManager가 이 설정을 주입받아 써요.
 */
package com.sunmo.stockplatform.kis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * KIS 연동 설정값 묶음이에요. 환경변수나 설정 파일의 kis.* 값이 자동으로 채워져요.
 * 가게 벽에 붙여 둔 거래처 연락처 안내판처럼, 다른 코드가 필요할 때 읽어 가요.
 * 각 필드: enabled(연동 사용 여부), baseUrl(KIS 주소), appKey/appSecret(인증정보), tokenRefreshSkew(만료 전 미리 갱신할 여유),
 * connectTimeout/readTimeout(연결·응답 대기 한도), master(종목 마스터 파일 설정).
 */
@ConfigurationProperties(prefix = "kis")
public record KisProperties(
        boolean enabled,
        URI baseUrl,
        String appKey,
        String appSecret,
        Duration tokenRefreshSkew,
        Duration connectTimeout,
        Duration readTimeout,
        Master master) {
    /**
     * 종목 마스터 파일 동기화 설정이에요.
     * syncEnabled(자동 동기화 사용), cron(실행 일정 표현식), kospiUrl/kosdaqUrl(시장별 마스터 파일 주소)을 담아요.
     */
    public record Master(boolean syncEnabled, String cron, URI kospiUrl, URI kosdaqUrl) {
    }

    /**
     * KIS 연동이 켜져 있고 앱 키와 시크릿이 모두 있는지 확인해요.
     * 받는 것: 없음. 돌려주는 것: 없음, 조건이 안 맞으면 IllegalStateException을 던져요.
     */
    public void requireCredentials() {
        if (!enabled || appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("KIS integration is disabled or credentials are missing");
        }
    }
}
