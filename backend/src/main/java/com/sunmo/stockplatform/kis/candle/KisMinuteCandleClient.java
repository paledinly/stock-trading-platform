/*
 * KIS 당일 분봉 조회 API로 지정 시각 이전의 1분봉들을 받아 오는 파일이에요.
 * 응답의 날짜와 시각을 서울 시간 기준 Instant로 바꾸고, 가격이 0 이하이면 실패로 처리해요.
 * candle 패키지의 분봉 보충(빠진 5분봉 메우기) 작업이 이 클라이언트를 호출하는 것으로 보여요.
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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * KIS에서 당일 1분봉 목록을 받아 MinuteCandle 목록으로 바꿔 주는 클라이언트예요.
 * CCTV 녹화 기록에서 "이 시각까지의 분 단위 장면"을 뽑아 달라고 요청하는 것과 같아요.
 */
@Component
public class KisMinuteCandleClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String TR_ID = "FHKST03010200";
    // 과거 날짜 분봉 조회(주식일별분봉조회)예요. 한 번에 최대 120분, 지정 시각에서 거꾸로 받아요.
    private static final String HISTORY_ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
    private static final String HISTORY_TR_ID = "FHKST03010230";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KisMinuteCandleClient.class);
    // 영업일자(stck_bsop_date)가 없어 버린 분봉 행의 누적 개수예요(서버 시작 이후).
    private final java.util.concurrent.atomic.AtomicLong rowsWithoutDate = new java.util.concurrent.atomic.AtomicLong();

    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    /**
     * 분봉 조회에 필요한 부품을 받아요.
     * 받는 것: KIS용 RestClient, KIS 설정, 토큰(API 호출용 임시 출입증) 관리자, 호출 속도 조절기. 돌려주는 것: 없음(생성자).
     */
    public KisMinuteCandleClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /**
     * 지정 시각 이전의 당일 1분봉을 조회해요.
     * rate limit(일정 시간당 호출 횟수 제한)과 회로 차단기(실패가 잦으면 잠시 호출을 막는 장치)가 걸려 있어요.
     * 받는 것: 종목코드, 조회 기준 시각(이 시각까지). 돌려주는 것: KIS 응답 순서대로의 분봉 목록.
     */
    @RateLimiter(name = "kisMinuteCandle")
    @CircuitBreaker(name = "kisMinuteCandle")
    public List<MinuteCandle> fetch(String stockCode, LocalTime through) {
        // 요청: 인증정보 확인 후 공용 속도 조절기를 거쳐 KIS를 불러요.
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
        // 처리: 각 행의 날짜와 시각을 서울 시간 Instant로 바꾸고 가격을 검증해 담아요.
        List<MinuteCandle> result = new ArrayList<>();
        int skippedWithoutDate = 0;
        for (JsonNode row : body.path("output2")) {
            // 영업일자(stck_bsop_date)가 없거나 형식이 틀린 행은 서버 날짜로 추정하지 않고 버려요.
            // 자정 이후나 휴장일에 직전 거래일 분봉에 오늘 날짜가 잘못 붙는 일을 막기 위해서예요. 버린 개수는 세고 로그로 남겨요.
            String rawDate = row.path("stck_bsop_date").asText().trim();
            if (rawDate.length() != 8) {
                skippedWithoutDate++;
                continue;
            }
            String rawTime = row.path("stck_cntg_hour").asText();
            if (rawTime.length() != 6)
                continue;
            Instant start = LocalDate.parse(rawDate, DATE).atTime(LocalTime.parse(rawTime, TIME))
                    .atZone(SEOUL).toInstant();
            // 종가 자리에는 그 분의 현재가(stck_prpr)를 써요.
            result.add(new MinuteCandle(start, decimal(row, "stck_oprc"), decimal(row, "stck_hgpr"),
                    decimal(row, "stck_lwpr"), decimal(row, "stck_prpr"), number(row, "cntg_vol"),
                    nullableDecimal(row, "acml_tr_pbmn")));
        }
        if (skippedWithoutDate > 0) {
            rowsWithoutDate.addAndGet(skippedWithoutDate);
            log.warn("KIS minute candle rows without business date skipped stock={} rows={}", stockCode, skippedWithoutDate);
        }
        return result;
    }

    /** 영업일자가 없어 버린 분봉 행의 누적 개수를 돌려줘요(진단·테스트용). */
    public long rowsWithoutDate() {
        return rowsWithoutDate.get();
    }


    /**
     * 하는 일: 과거 날짜 하루의 분봉을 지정 시각에서 거꾸로 최대 120분 받아요(연구용 장중 데이터 수집).
     * 받는 것: 종목코드, 날짜, 시각. 돌려주는 것: 그 날짜의 분봉만(다른 날 행은 버려요). 가격이 0 이하인 행은 건너뛰어요.
     */
    @RateLimiter(name = "kisMinuteCandle")
    @CircuitBreaker(name = "kisMinuteCandle")
    public List<MinuteCandle> fetchHistory(String stockCode, LocalDate date, LocalTime through) {
        properties.requireCredentials();
        JsonNode body = requests.execute(false, () -> {
            JsonNode response = client.get().uri(builder -> builder.path(HISTORY_ENDPOINT)
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", stockCode)
                    .queryParam("FID_INPUT_HOUR_1", through.format(TIME))
                    .queryParam("FID_INPUT_DATE_1", date.format(DATE))
                    .queryParam("FID_PW_DATA_INCU_YN", "Y")
                    .queryParam("FID_FAKE_TICK_INCU_YN", "")
                    .build())
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", HISTORY_TR_ID)
                    .header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText()))
                KisResponseErrors.failure("KIS minute history request failed",
                        response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText(),
                        response == null ? "empty response" : response.path("msg1").asText());
            return response;
        });
        List<MinuteCandle> result = new ArrayList<>();
        for (JsonNode row : body.path("output2")) {
            String rawDate = row.path("stck_bsop_date").asText().trim();
            String rawTime = row.path("stck_cntg_hour").asText().trim();
            if (rawDate.length() != 8 || rawTime.length() != 6 || !LocalDate.parse(rawDate, DATE).equals(date))
                continue;
            BigDecimal open = nullableDecimal(row, "stck_oprc"), high = nullableDecimal(row, "stck_hgpr");
            BigDecimal low = nullableDecimal(row, "stck_lwpr"), close = nullableDecimal(row, "stck_prpr");
            if (open == null || high == null || low == null || close == null || open.signum() <= 0
                    || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0)
                continue;
            Instant start = date.atTime(LocalTime.parse(rawTime, TIME)).atZone(SEOUL).toInstant();
            result.add(new MinuteCandle(start, open, high, low, close, number(row, "cntg_vol"),
                    nullableDecimal(row, "acml_tr_pbmn")));
        }
        return result;
    }

    /**
     * 과거 분봉 조회가 어디까지 되는지 확인한 결과예요. rtCd·msgCd·message는 KIS 응답 그대로(인증정보 없음),
     * rows는 받은 분봉 수, firstAt·lastAt은 받은 분봉의 처음·마지막 시각, dates는 들어 있는 영업일이에요.
     */
    public record HistoryProbe(String stockCode, LocalDate requestedDate, String requestedTime, String rtCd, String msgCd,
            String message, int rows, Instant firstAt, Instant lastAt, List<LocalDate> dates) { }

    /**
     * 하는 일: 지정한 날짜·시각까지의 과거 분봉을 한 번 조회하고, 응답 상태와 받은 범위를 요약해요(진단용).
     * 받는 것: 종목코드, 날짜, 시각. 돌려주는 것: HistoryProbe. KIS가 오류를 줘도 예외 대신 상태를 담아 돌려줘요.
     * 장중 분봉 깔때기를 과거로 재현할 수 있는지(지원 기간) 확인하려고 만들었어요.
     */
    @RateLimiter(name = "kisMinuteCandle")
    public HistoryProbe probeHistory(String stockCode, LocalDate date, LocalTime through) {
        properties.requireCredentials();
        JsonNode body = requests.execute(false, () -> client.get().uri(builder -> builder.path(HISTORY_ENDPOINT)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", stockCode)
                .queryParam("FID_INPUT_HOUR_1", through.format(TIME))
                .queryParam("FID_INPUT_DATE_1", date.format(DATE))
                .queryParam("FID_PW_DATA_INCU_YN", "Y")
                .queryParam("FID_FAKE_TICK_INCU_YN", "")
                .build())
                .header("authorization", "Bearer " + tokens.getAccessToken())
                .header("appkey", properties.appKey())
                .header("appsecret", properties.appSecret())
                .header("tr_id", HISTORY_TR_ID)
                .header("custtype", "P")
                .retrieve().body(JsonNode.class));
        if (body == null)
            return new HistoryProbe(stockCode, date, through.format(TIME), null, "EMPTY_RESPONSE", "empty response",
                    0, null, null, List.of());
        Instant first = null, last = null;
        int rows = 0;
        java.util.TreeSet<LocalDate> dates = new java.util.TreeSet<>();
        for (JsonNode row : body.path("output2")) {
            String rawDate = row.path("stck_bsop_date").asText().trim();
            String rawTime = row.path("stck_cntg_hour").asText().trim();
            if (rawDate.length() != 8 || rawTime.length() != 6)
                continue;
            LocalDate day = LocalDate.parse(rawDate, DATE);
            Instant at = day.atTime(LocalTime.parse(rawTime, TIME)).atZone(SEOUL).toInstant();
            first = first == null || at.isBefore(first) ? at : first;
            last = last == null || at.isAfter(last) ? at : last;
            dates.add(day);
            rows++;
        }
        return new HistoryProbe(stockCode, date, through.format(TIME), body.path("rt_cd").asText(),
                body.path("msg_cd").asText(), body.path("msg1").asText(), rows, first, last, List.copyOf(dates));
    }

    /**
     * 필수 가격 필드를 읽어요.
     * 받는 것: KIS 분봉 한 행, 필드 이름. 돌려주는 것: 양수 가격, 비었거나 0 이하면 IllegalArgumentException을 던져요.
     */
    private BigDecimal decimal(JsonNode row, String field) {
        BigDecimal value = nullableDecimal(row, field);
        if (value == null || value.signum() <= 0)
            throw new IllegalArgumentException("Invalid KIS field: " + field);
        return value;
    }

    /**
     * 선택 숫자 필드를 읽어요.
     * 받는 것: KIS 분봉 한 행, 필드 이름. 돌려주는 것: 숫자 값, 비어 있으면 null.
     */
    private BigDecimal nullableDecimal(JsonNode row, String field) {
        String value = row.path(field).asText().trim();
        return value.isEmpty() ? null : new BigDecimal(value);
    }

    /**
     * 정수 필드(체결량)를 읽어요.
     * 받는 것: KIS 분봉 한 행, 필드 이름. 돌려주는 것: 정수 값, 비어 있으면 0.
     */
    private long number(JsonNode row, String field) {
        String value = row.path(field).asText().trim();
        // ⚠️ 확인 필요: 체결량이 비어 있으면 결측 대신 0으로 바꿔요. "결측을 0으로 조용히 바꾸지 않는다"는 원칙과 어긋날 수 있어요.
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }
}
