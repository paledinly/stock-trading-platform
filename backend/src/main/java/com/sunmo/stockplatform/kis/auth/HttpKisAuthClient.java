/*
 * KIS(한국투자증권) Open API에 앱 키와 시크릿을 보내 접근 토큰을 새로 발급받는 파일이에요.
 * 발급 응답이 비었거나 통신이 실패하면 프로젝트 공통 오류(ApplicationException)로 바꿔 던져요.
 * KisTokenManager가 토큰이 없거나 만료가 가까울 때 이 클라이언트를 호출해요.
 */
package com.sunmo.stockplatform.kis.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.kis.config.KisProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

/**
 * HTTP로 KIS 인증 서버에 직접 토큰(API를 쓸 수 있다는 임시 출입증)을 요청하는 구현체예요.
 * 회사 안내 데스크에 신분증(앱 키·시크릿)을 내고 하루짜리 출입증을 받아 오는 심부름꾼과 같아요.
 */
@Component
class HttpKisAuthClient implements KisAuthClient {
    private final RestClient restClient;
    private final KisProperties properties;

    /**
     * 의존성 주입(스프링이 필요한 부품을 대신 만들어 넣어 주는 것)으로 HTTP 클라이언트와 KIS 설정을 받아요.
     * 받는 것: KIS 기본 주소가 설정된 RestClient, KIS 설정값. 돌려주는 것: 없음(생성자).
     */
    HttpKisAuthClient(RestClient kisRestClient, KisProperties properties) {
        this.restClient = kisRestClient;
        this.properties = properties;
    }

    /**
     * KIS에 새 접근 토큰을 발급해 달라고 요청해요.
     * rate limit(일정 시간당 호출 횟수 제한)과 회로 차단기(실패가 잦으면 잠시 호출을 막는 장치)가 함께 걸려 있어요.
     * 받는 것: 없음(설정의 앱 키·시크릿 사용). 돌려주는 것: 토큰 값과 만료 시각을 담은 KisAccessToken.
     */
    @Override
    @RateLimiter(name = "kisAuth")
    @CircuitBreaker(name = "kisAuth")
    public KisAccessToken issueToken() {
        try {
            // 준비: 인증정보가 없으면 IllegalStateException이 나고, 아래에서 "설정 안 됨" 오류로 바뀌어요.
            properties.requireCredentials();
            var response = restClient.post()
                    .uri("/oauth2/tokenP")
                    .body(new TokenRequest("client_credentials", properties.appKey(), properties.appSecret()))
                    .retrieve()
                    .body(TokenResponse.class);
            // 결과 확인: 빈 토큰을 정상값처럼 쓰지 않도록 바로 실패 처리해요.
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new ApplicationException(ErrorCode.KIS_AUTHENTICATION_FAILED, HttpStatus.BAD_GATEWAY,
                        "KIS returned an empty access token");
            }
            return new KisAccessToken(response.accessToken(), resolveExpiry(response));
        } catch (IllegalStateException exception) {
            throw new ApplicationException(ErrorCode.KIS_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
                    "KIS integration is not configured", exception);
        } catch (RestClientException exception) {
            throw new ApplicationException(ErrorCode.KIS_AUTHENTICATION_FAILED, HttpStatus.BAD_GATEWAY,
                    "KIS authentication failed", exception);
        }
    }

    /**
     * 토큰 응답에서 만료 시각을 계산해요.
     * 받는 것: KIS 토큰 응답. 돌려주는 것: 지금 + 유효 초(expires_in), 값이 없으면 지금 + 23시간.
     */
    private Instant resolveExpiry(TokenResponse response) {
        if (response.expiresIn() != null && response.expiresIn() > 0) {
            return Instant.now().plusSeconds(response.expiresIn());
        }
        // KIS 토큰은 보통 24시간 유효해서, 정보가 없으면 1시간 여유를 둔 23시간으로 가정하는 것으로 보여요.
        return Instant.now().plusSeconds(23 * 60 * 60);
    }

    /** KIS 토큰 발급 요청 본문이에요. 필드 이름은 KIS가 요구하는 JSON 키와 같아요. */
    private record TokenRequest(@JsonProperty("grant_type") String grantType,
            String appkey,
            String appsecret) {
    }

    /** KIS 토큰 발급 응답 본문이에요. 토큰 값, 유효 초, 만료 일시 문자열을 담아요. */
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("access_token_token_expired") String expiresAt) {
    }
}
