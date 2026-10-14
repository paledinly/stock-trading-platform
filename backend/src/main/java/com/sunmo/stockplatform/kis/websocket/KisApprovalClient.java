/*
 * KIS 실시간 WebSocket(서버와 계속 연결을 열어 두고 데이터를 실시간으로 주고받는 통신 방식)에 접속할 때 필요한 접속키(approval key)를 발급받아요.
 * 앱키와 시크릿으로 KIS /oauth2/Approval에 요청하고, 받은 키 문자열만 돌려줘요.
 * KisRealtimeClient가 WebSocket에 연결하기 직전마다 이 클래스를 불러요.
 */
package com.sunmo.stockplatform.kis.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sunmo.stockplatform.kis.config.KisProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

/**
 * 실시간 시세 방에 들어가기 위한 입장권(접속키)을 받아 오는 클래스예요.
 * 공연장 매표소에서 신분증(앱키·시크릿)을 보여 주고 그날 쓸 입장 팔찌를 받아 오는 일과 같아요.
 */
@Component
public class KisApprovalClient {
    private final RestClient client;
    private final KisProperties properties;

    /**
     * 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 방식)으로 KIS REST 클라이언트와 설정값을 받아요.
     * 받는 것: KIS용 RestClient, KIS 설정(앱키·시크릿). 돌려주는 것: 접속키 발급 준비가 끝난 객체.
     */
    public KisApprovalClient(RestClient kisRestClient, KisProperties properties) {
        this.client = kisRestClient;
        this.properties = properties;
    }

    /**
     * KIS에 WebSocket 접속키를 새로 발급받아요.
     * 받는 것: 없음(설정의 앱키·시크릿 사용). 돌려주는 것: 접속키 문자열.
     * 인증정보가 없거나 응답에 키가 비어 있으면 IllegalStateException을 던져요.
     */
    public String issue() {
        properties.requireCredentials();
        Response response = client.post().uri("/oauth2/Approval").body(Map.of("grant_type", "client_credentials",
                "appkey", properties.appKey(), "secretkey", properties.appSecret())).retrieve().body(Response.class);
        if (response == null || response.key() == null || response.key().isBlank())
            throw new IllegalStateException("KIS websocket approval failed");
        return response.key();
    }

    /** KIS 응답 JSON에서 approval_key 필드만 꺼내 담는 내부 기록이에요. */
    private record Response(@JsonProperty("approval_key") String key) {
    }
}
