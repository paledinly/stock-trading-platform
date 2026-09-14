package com.sunmo.stockplatform.kis.candle;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class KisMinuteCandleClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String TR_ID = "FHKST03010200";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    public KisMinuteCandleClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    @RateLimiter(name = "kisMinuteCandle")
    @CircuitBreaker(name = "kisMinuteCandle")
    public List<MinuteCandle> fetch(String stockCode, LocalTime through) {
        properties.requireCredentials();
        JsonNode body = requests.execute(false, () -> {
        JsonNode response = client.get().uri(builder -> builder.path(ENDPOINT)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", stockCode)
                .queryParam("FID_INPUT_HOUR_1", through.format(TIME))
                .queryParam("FID_PW_DATA_INCU_YN", "Y")
                .queryParam("FID_ETC_CLS_CODE", "")
                .build())
                .header("authorization", "Bearer " + tokens.getAccessToken())
                .header("appkey", properties.appKey())
                .header("appsecret", properties.appSecret())
                .header("tr_id", TR_ID)
                .header("custtype", "P")
                .retrieve().body(JsonNode.class);
        if (response == null || !"0".equals(response.path("rt_cd").asText())) {
            KisResponseErrors.failure("KIS minute candle request failed",
                    response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText(),
                    response == null ? "empty response" : response.path("msg1").asText());
        }
        return response;
        });
        List<MinuteCandle> result = new ArrayList<>();
        for (JsonNode row : body.path("output2")) {
            String rawDate = row.path("stck_bsop_date").asText(LocalDate.now(SEOUL).format(DATE));
            String rawTime = row.path("stck_cntg_hour").asText();
            if (rawTime.length() != 6)
                continue;
            Instant start = LocalDate.parse(rawDate, DATE).atTime(LocalTime.parse(rawTime, TIME))
                    .atZone(SEOUL).toInstant();
            result.add(new MinuteCandle(start, decimal(row, "stck_oprc"), decimal(row, "stck_hgpr"),
                    decimal(row, "stck_lwpr"), decimal(row, "stck_prpr"), number(row, "cntg_vol"),
                    nullableDecimal(row, "acml_tr_pbmn")));
        }
        return result;
    }

    private BigDecimal decimal(JsonNode row, String field) {
        BigDecimal value = nullableDecimal(row, field);
        if (value == null || value.signum() <= 0)
            throw new IllegalArgumentException("Invalid KIS field: " + field);
        return value;
    }

    private BigDecimal nullableDecimal(JsonNode row, String field) {
        String value = row.path(field).asText().trim();
        return value.isEmpty() ? null : new BigDecimal(value);
    }

    private long number(JsonNode row, String field) {
        String value = row.path(field).asText().trim();
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }
}
