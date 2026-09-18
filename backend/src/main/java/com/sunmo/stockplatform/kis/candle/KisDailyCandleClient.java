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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class KisDailyCandleClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String TR_ID = "FHKST03010100";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    public KisDailyCandleClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<DailyCandle> fetch(String stockCode, LocalDate from, LocalDate through) {
        properties.requireCredentials();
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid daily candle date range");
        JsonNode body = requests.execute(false, () -> {
            JsonNode response = client.get().uri(builder -> builder.path(ENDPOINT)
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", stockCode)
                    .queryParam("FID_INPUT_DATE_1", from.format(DATE))
                    .queryParam("FID_INPUT_DATE_2", through.format(DATE))
                    .queryParam("FID_PERIOD_DIV_CODE", "D")
                    .queryParam("FID_ORG_ADJ_PRC", "1")
                    .build())
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText())) {
                KisResponseErrors.failure("KIS daily candle request failed",
                        response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText(),
                        response == null ? "empty response" : response.path("msg1").asText());
            }
            return response;
        });
        List<DailyCandle> rows = new ArrayList<>();
        for (JsonNode row : body.path("output2")) {
            String rawDate = row.path("stck_bsop_date").asText();
            if (rawDate.length() != 8) continue;
            LocalDate date = LocalDate.parse(rawDate, DATE);
            if (date.isBefore(from) || date.isAfter(through)) continue;
            BigDecimal open = positive(row, "stck_oprc");
            BigDecimal high = positive(row, "stck_hgpr");
            BigDecimal low = positive(row, "stck_lwpr");
            BigDecimal close = positive(row, "stck_clpr");
            long volume = Long.parseLong(row.path("acml_vol").asText("0"));
            String rawValue = row.path("acml_tr_pbmn").asText();
            BigDecimal value = rawValue.isBlank() ? close.multiply(BigDecimal.valueOf(volume))
                    : new BigDecimal(rawValue);
            rows.add(new DailyCandle(date, open, high, low, close, volume, value));
        }
        return List.copyOf(rows);
    }

    private BigDecimal positive(JsonNode row, String field) {
        BigDecimal value = new BigDecimal(row.path(field).asText("0"));
        if (value.signum() <= 0) throw new IllegalArgumentException("Invalid KIS daily candle field: " + field);
        return value;
    }
}
