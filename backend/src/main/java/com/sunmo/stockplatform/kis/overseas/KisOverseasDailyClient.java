/*
 * KIS 해외 일봉 조회예요. 미국 지수·환율은 해외주식 종목/지수/환율 기간별시세(FHKST03030100),
 * ETF(EWY)는 해외주식 기간별시세(HHDFS76240000)로 받아요. 2026-10-23 실호출로 SPX·COMP·NDX·.DJI·VIX·SOX(N),
 * FX@KRW(X), EWY(AMS)가 조회되는 것을 확인했어요. OverseasDailyService(해외 일봉 기록)가 써요. 점수에 쓰지 않아요.
 */
package com.sunmo.stockplatform.kis.overseas;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 해외 일봉 한 건을 받는 KIS 조회기예요. 날짜는 거래소 현지 거래일이에요. */
@Component
public class KisOverseasDailyClient {
    private static final String CHART_ENDPOINT = "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
    private static final String CHART_TR_ID = "FHKST03030100";
    private static final String ETF_ENDPOINT = "/uapi/overseas-price/v1/quotations/dailyprice";
    private static final String ETF_TR_ID = "HHDFS76240000";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    /** 해외 일봉 한 행이에요. volume은 KIS 단위 그대로예요(지수·환율은 비어 있을 수 있어요). */
    public record OverseasBar(LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            Long volume) { }

    public KisOverseasDailyClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /**
     * 하는 일: 해외 지수(N)·환율(X) 일봉을 from~through로 한 번 요청해요(KIS가 최근 쪽부터 일부만 줄 수 있어요).
     * 받는 것: 시장 구분(N 지수, X 환율), 코드(SPX, FX@KRW 등), 시작일, 끝일. 돌려주는 것: 날짜 오름차순, 범위 밖·이상한 행 제외.
     */
    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<OverseasBar> fetchIndex(String marketDiv, String code, LocalDate from, LocalDate through) {
        properties.requireCredentials();
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid overseas daily date range");
        JsonNode body = call(CHART_ENDPOINT, CHART_TR_ID, Map.of("FID_COND_MRKT_DIV_CODE", marketDiv,
                "FID_INPUT_ISCD", code, "FID_INPUT_DATE_1", from.format(DATE), "FID_INPUT_DATE_2", through.format(DATE),
                "FID_PERIOD_DIV_CODE", "D"));
        return parse(body, from, through, "stck_bsop_date", "ovrs_nmix_oprc", "ovrs_nmix_hgpr", "ovrs_nmix_lwpr",
                "ovrs_nmix_prpr", "acml_vol");
    }

    /**
     * 하는 일: 해외 ETF 일봉을 through부터 거꾸로 최대 100행 요청해요(수정주가).
     * 받는 것: 거래소 코드(AMS 등), 종목(EWY), 시작일(이보다 앞선 행은 버려요), 끝일. 돌려주는 것: 날짜 오름차순.
     */
    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<OverseasBar> fetchEtf(String exchange, String symbol, LocalDate from, LocalDate through) {
        properties.requireCredentials();
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid overseas daily date range");
        JsonNode body = call(ETF_ENDPOINT, ETF_TR_ID, Map.of("AUTH", "", "EXCD", exchange, "SYMB", symbol, "GUBN", "0",
                "BYMD", through.format(DATE), "MODP", "1"));
        return parse(body, from, through, "xymd", "open", "high", "low", "clos", "tvol");
    }

    private JsonNode call(String endpoint, String trId, Map<String, String> params) {
        return requests.execute(false, () -> {
            JsonNode response = client.get().uri(builder -> {
                        builder.path(endpoint);
                        params.forEach(builder::queryParam);
                        return builder.build();
                    })
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText())) {
                KisResponseErrors.failure("KIS overseas daily request failed",
                        response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText(),
                        response == null ? "empty response" : response.path("msg1").asText());
            }
            return response;
        });
    }

    /** 응답 output2를 일봉 목록으로 바꿔요. 날짜가 8자리가 아니거나 종가가 0 이하인 행은 버려요. */
    static List<OverseasBar> parse(JsonNode body, LocalDate from, LocalDate through, String dateField, String openField,
            String highField, String lowField, String closeField, String volumeField) {
        List<OverseasBar> rows = new ArrayList<>();
        for (JsonNode row : body.path("output2")) {
            String rawDate = row.path(dateField).asText().trim();
            BigDecimal close = decimal(row, closeField);
            if (rawDate.length() != 8 || close == null || close.signum() <= 0)
                continue;
            LocalDate date = LocalDate.parse(rawDate, DATE);
            if (date.isBefore(from) || date.isAfter(through))
                continue;
            BigDecimal volume = decimal(row, volumeField);
            rows.add(new OverseasBar(date, decimal(row, openField), decimal(row, highField), decimal(row, lowField), close,
                    volume == null ? null : volume.longValue()));
        }
        rows.sort(Comparator.comparing(OverseasBar::tradeDate));
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
