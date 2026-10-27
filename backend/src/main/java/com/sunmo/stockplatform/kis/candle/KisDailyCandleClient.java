/*
 * KIS 기간별 시세 API로 종목의 일봉(하루 단위 가격)을 날짜 범위만큼 받아 오는 파일이에요.
 * 원주가(운영용)와 수정주가(연구용, 액면분할 등을 반영해 과거 가격을 고친 값) 두 가지 방식으로 조회할 수 있어요.
 * candle 패키지의 일봉 보충과 수정주가 일봉 수집 작업이 이 클라이언트를 호출해요.
 */
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

/**
 * KIS에서 종목별 일봉 목록을 받아 DailyCandle 목록으로 바꿔 주는 클라이언트예요.
 * 도서관 사서에게 "이 종목의 이 기간 일별 기록 주세요"라고 요청해 표로 정리해 받는 것과 같아요.
 */
@Component
public class KisDailyCandleClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String TR_ID = "FHKST03010100";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    /**
     * 일봉 조회에 필요한 부품을 받아요.
     * 받는 것: KIS용 RestClient, KIS 설정, 토큰(API 호출용 임시 출입증) 관리자, 호출 속도 조절기. 돌려주는 것: 없음(생성자).
     */
    public KisDailyCandleClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /** Original (unadjusted) prices; rows with a non-positive price (halted days) are skipped. Used by the operational 1D candles. */
    /**
     * 원주가 일봉을 조회해요. 가격이 0 이하인 행(거래정지일 등)은 그 행만 건너뛰어요. 운영용 1D 봉에 쓰여요.
     * rate limit(일정 시간당 호출 횟수 제한)과 회로 차단기(실패가 잦으면 잠시 호출을 막는 장치)가 걸려 있어요.
     * 받는 것: 종목코드, 시작일, 종료일. 돌려주는 것: 범위 안 일봉 목록(KIS 응답 순서, 보통 최신이 먼저).
     */
    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<DailyCandle> fetch(String stockCode, LocalDate from, LocalDate through) {
        return request(stockCode, from, through, false);
    }

    /**
     * Adjusted (수정주가) prices for research. KIS returns at most 100 rows per call, newest first. Rows without a
     * positive price (e.g. a halted day) are skipped instead of failing the whole stock.
     */
    /**
     * 연구용 수정주가 일봉을 조회해요. KIS는 한 번에 최대 100행을 최신순으로 줘요.
     * 가격이 0 이하인 행(예: 거래정지일)은 종목 전체를 실패시키지 않고 건너뛰어요.
     * 받는 것: 종목코드, 시작일, 종료일. 돌려주는 것: 범위 안 수정주가 일봉 목록.
     */
    @RateLimiter(name = "kisDailyCandle")
    @CircuitBreaker(name = "kisDailyCandle")
    public List<DailyCandle> fetchAdjusted(String stockCode, LocalDate from, LocalDate through) {
        return request(stockCode, from, through, true);
    }

    /**
     * 원주가와 수정주가 조회가 함께 쓰는 실제 요청 로직이에요.
     * 받는 것: 종목코드, 시작일, 종료일, 수정주가 여부. 돌려주는 것: 변경 불가능한 일봉 목록.
     */
    private List<DailyCandle> request(String stockCode, LocalDate from, LocalDate through, boolean adjusted) {
        // 준비: 인증정보와 날짜 범위를 먼저 확인해요.
        properties.requireCredentials();
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid daily candle date range");
        // 요청: 공용 속도 조절기를 거쳐 KIS를 부르고, 실패 응답은 예외로 바꿔요.
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
        // 처리: 날짜가 이상하거나 범위 밖인 행은 건너뛰고, 나머지를 DailyCandle로 바꿔요.
        List<DailyCandle> rows = new ArrayList<>();
        for (JsonNode row : body.path("output2")) {
            String rawDate = row.path("stck_bsop_date").asText();
            if (rawDate.length() != 8) continue;
            LocalDate date = LocalDate.parse(rawDate, DATE);
            if (date.isBefore(from) || date.isAfter(through)) continue;
            // 가격이 0 이하인 행(거래정지일 등)은 그 행만 건너뛰어요. 예전에는 종목 전체 조회가 실패했어요.
            if (!allPositive(row)) continue;
            BigDecimal open = price(row, "stck_oprc");
            BigDecimal high = price(row, "stck_hgpr");
            BigDecimal low = price(row, "stck_lwpr");
            BigDecimal close = price(row, "stck_clpr");
            String rawVolume = row.path("acml_vol").asText("").trim();
            long volume = rawVolume.isEmpty() ? 0L : Long.parseLong(rawVolume);
            // 거래대금이 비어 있으면 종가 × 거래량으로 근사해요(정확한 값은 아니에요).
            String rawValue = row.path("acml_tr_pbmn").asText().trim();
            BigDecimal value = rawValue.isBlank() ? close.multiply(BigDecimal.valueOf(volume))
                    : new BigDecimal(rawValue);
            rows.add(new DailyCandle(date, open, high, low, close, volume, value));
        }
        return List.copyOf(rows);
    }

    /**
     * 시가, 고가, 저가, 종가가 모두 0보다 큰지 확인해요.
     * 받는 것: KIS 일봉 한 행. 돌려주는 것: 네 가격이 모두 양수면 true.
     */
    private boolean allPositive(JsonNode row) {
        for (String field : List.of("stck_oprc", "stck_hgpr", "stck_lwpr", "stck_clpr"))
            if (price(row, field).signum() <= 0) return false;
        return true;
    }

    /**
     * 지정한 가격 필드를 숫자로 읽어요.
     * 받는 것: KIS 일봉 한 행, 필드 이름. 돌려주는 것: 가격, 비어 있으면 0.
     */
    private BigDecimal price(JsonNode row, String field) {
        String raw = row.path(field).asText("").trim();
        return raw.isEmpty() ? BigDecimal.ZERO : new BigDecimal(raw);
    }
}
