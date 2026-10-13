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

    /** Original (unadjusted) prices; a malformed row fails the request. Used by the operational 1D candles. */
    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<DailyCandle> fetch(String stockCode, LocalDate from, LocalDate through) {
        return request(stockCode, from, through, false);
    }

    /**
     * Adjusted (수정주가) prices for research. KIS returns at most 100 rows per call, newest first. Rows without a
     * positive price (e.g. a halted day) are skipped instead of failing the whole stock.
     */
    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<DailyCandle> fetchAdjusted(String stockCode, LocalDate from, LocalDate through) {
        return request(stockCode, from, through, true);
    }

    private List<DailyCandle> request(String stockCode, LocalDate from, LocalDate through, boolean adjusted) {
        properties.requireCredentials();
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid daily candle date range");
        JsonNode body = requests.execute(false, () -> {
            JsonNode response = client.get().uri(builder -> builder.path(ENDPOINT)
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", stockCode)
                    .queryParam("FID_INPUT_DATE_1", from.format(DATE))
                    .queryParam("FID_INPUT_DATE_2", through.format(DATE))
                    .queryParam("FID_PERIOD_DIV_CODE", "D")
                    // 0 = adjusted (수정주가), 1 = original (원주가).
                    .queryParam("FID_ORG_ADJ_PRC", adjusted ? "0" : "1")
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
            if (adjusted && !allPositive(row)) continue;
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

    private boolean allPositive(JsonNode row) {
        for (String field : List.of("stck_oprc", "stck_hgpr", "stck_lwpr", "stck_clpr"))
            if (new BigDecimal(row.path(field).asText("0")).signum() <= 0) return false;
        return true;
    }

    private BigDecimal positive(JsonNode row, String field) {
        BigDecimal value = new BigDecimal(row.path(field).asText("0"));
        if (value.signum() <= 0) throw new IllegalArgumentException("Invalid KIS daily candle field: " + field);
        return value;
    }
}
