/*
 * KIS 국내업종 기간별 시세(FHKUP03500100)로 KOSPI(0001)·KOSDAQ(1001) 지수 일봉을 받아요.
 * 한 번에 최근 쪽 50행만 오고 다음 페이지 표시가 없어서(2026-10-16 실호출 확인), 끝 날짜를 앞으로 옮겨 가며 나눠 받아요.
 * MarketIndexService(지수 일봉 저장·최신 지수)가 써요. 외부 요인 연구용 기록이며 점수에 쓰지 않아요.
 */
package com.sunmo.stockplatform.kis.index;

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

/** 지수 일봉 한 건이에요. volume·tradingValue는 KIS 단위 그대로예요(acml_vol, acml_tr_pbmn). */
@Component
public class KisIndexDailyClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";
    private static final String TR_ID = "FHKUP03500100";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    /** 지수 일봉 한 행이에요. */
    public record IndexDailyBar(LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            Long volume, BigDecimal tradingValue) { }

    /** 받는 것: KIS용 RestClient, 설정, 토큰 관리자, 공용 요청 실행기. */
    public KisIndexDailyClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /**
     * 하는 일: 지수 코드의 from~through 일봉을 한 번 요청해요(KIS가 through부터 거꾸로 최대 50행을 줘요).
     * 받는 것: 지수 코드(0001 KOSPI, 1001 KOSDAQ), 시작일, 끝일. 돌려주는 것: 날짜 오름차순 일봉, 범위 밖·이상한 행은 빼요.
     */
    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<IndexDailyBar> fetch(String indexCode, LocalDate from, LocalDate through) {
        properties.requireCredentials();
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid index daily date range");
        JsonNode body = requests.execute(false, () -> {
            JsonNode response = client.get().uri(builder -> builder.path(ENDPOINT)
                    .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                    .queryParam("FID_INPUT_ISCD", indexCode)
                    .queryParam("FID_INPUT_DATE_1", from.format(DATE))
                    .queryParam("FID_INPUT_DATE_2", through.format(DATE))
                    .queryParam("FID_PERIOD_DIV_CODE", "D")
                    .build())
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText())) {
                KisResponseErrors.failure("KIS index daily request failed",
                        response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText(),
                        response == null ? "empty response" : response.path("msg1").asText());
            }
            return response;
        });
        List<IndexDailyBar> rows = new ArrayList<>();
        for (JsonNode row : body.path("output2")) {
            String rawDate = row.path("stck_bsop_date").asText().trim();
            BigDecimal close = decimal(row, "bstp_nmix_prpr");
            if (rawDate.length() != 8 || close == null || close.signum() <= 0)
                continue;
            LocalDate date = LocalDate.parse(rawDate, DATE);
            if (date.isBefore(from) || date.isAfter(through))
                continue;
            BigDecimal volume = decimal(row, "acml_vol");
            rows.add(new IndexDailyBar(date, decimal(row, "bstp_nmix_oprc"), decimal(row, "bstp_nmix_hgpr"),
                    decimal(row, "bstp_nmix_lwpr"), close, volume == null ? null : volume.longValue(),
                    decimal(row, "acml_tr_pbmn")));
        }
        rows.sort(java.util.Comparator.comparing(IndexDailyBar::tradeDate));
        return rows;
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        String value = row.path(field).asText().trim().replace(",", "");
        if (value.isEmpty())
            return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
