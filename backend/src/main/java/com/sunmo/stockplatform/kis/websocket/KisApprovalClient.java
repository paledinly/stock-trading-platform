package com.sunmo.stockplatform.kis.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sunmo.stockplatform.kis.config.KisProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Component
public class KisApprovalClient {
    private final RestClient client;
    private final KisProperties properties;

    public KisApprovalClient(RestClient kisRestClient, KisProperties properties) {
        this.client = kisRestClient;
        this.properties = properties;
    }

    public String issue() {
        properties.requireCredentials();
        Response response = client.post().uri("/oauth2/Approval").body(Map.of("grant_type", "client_credentials",
                "appkey", properties.appKey(), "secretkey", properties.appSecret())).retrieve().body(Response.class);
        if (response == null || response.key() == null || response.key().isBlank())
            throw new IllegalStateException("KIS websocket approval failed");
        return response.key();
    }

    private record Response(@JsonProperty("approval_key") String key) {
    }
}
