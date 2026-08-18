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

@Component
class HttpKisAuthClient implements KisAuthClient {
    private final RestClient restClient;
    private final KisProperties properties;

    HttpKisAuthClient(RestClient kisRestClient, KisProperties properties) {
        this.restClient = kisRestClient;
        this.properties = properties;
    }

    @Override
    @RateLimiter(name = "kisAuth")
    @CircuitBreaker(name = "kisAuth")
    public KisAccessToken issueToken() {
        try {
            properties.requireCredentials();
            var response = restClient.post()
                    .uri("/oauth2/tokenP")
                    .body(new TokenRequest("client_credentials", properties.appKey(), properties.appSecret()))
                    .retrieve()
                    .body(TokenResponse.class);
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

    private Instant resolveExpiry(TokenResponse response) {
        if (response.expiresIn() != null && response.expiresIn() > 0) {
            return Instant.now().plusSeconds(response.expiresIn());
        }
        return Instant.now().plusSeconds(23 * 60 * 60);
    }

    private record TokenRequest(@JsonProperty("grant_type") String grantType,
                                String appkey,
                                String appsecret) {
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") Long expiresIn,
                                 @JsonProperty("access_token_token_expired") String expiresAt) {
    }
}
