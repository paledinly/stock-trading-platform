/*
 * 수급(외국인·기관·프로그램 순매수) 연구 자료를 KIS REST API로 주기적으로 받아 Trajectory 맥락 대기열에 넣는 수집기예요.
 * 외국인·기관은 KIS 가집계 순위 응답(추정치)에서, 프로그램은 종목별 KRX 프로그램 매매 응답에서 누적 순매수 수량을 꺼내요.
 * 기본 꺼짐이고 실서버(openapi.koreainvestment.com)에서만 동작해요. TrajectoryController의 /flow/status가 상태를 보여 줘요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/**
 * 최근 관측 종목들의 수급 누적값을 몇 분마다 조회해 기록하는 수집기예요.
 * 학교 매점 앞에서 몇 분마다 "외국인 손님·단체 손님이 지금까지 몇 개 샀는지" 누적 장부를 훑어 적는 조사원과 같아요.
 * 순위 응답에 없는 종목은 0이 아니라 "없음"으로 남겨요.
 */
@Component
public class InvestorFlowCollector {
    private final InvestorFlowProperties policy;
    private final TrajectoryProperties trajectory;
    private final TrajectoryStore store;
    private final TrajectoryService service;
    private final RestClient client;
    private final KisProperties kis;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;
    private final ClosingTradingCalendar calendar;
    private long failures;
    // "PROGRAM EGW00201", "ESTIMATE HTTP 500" 같은 "종류 오류 코드"별 실패 수예요(서버 시작 이후 누적).
    private final Map<String, Long> failureCodes = new java.util.HashMap<>();
    private Instant lastCompletedAt;

    /** 하는 일: 설정·저장소·서비스·KIS 접속 도구·달력을 받아 보관해요. 받는 것: 각 의존 객체. 돌려주는 것: 수집기 객체예요. */
    public InvestorFlowCollector(InvestorFlowProperties policy, TrajectoryProperties trajectory,
            TrajectoryStore store, TrajectoryService service, RestClient kisRestClient, KisProperties kis,
            KisTokenManager tokens, KisRequestExecutor requests, ClosingTradingCalendar calendar) {
        this.policy = policy; this.trajectory = trajectory; this.store = store; this.service = service;
        client = kisRestClient; this.kis = kis; this.tokens = tokens; this.requests = requests; this.calendar = calendar;
    }

    /**
     * 하는 일: (기본 5분마다) 최근 1분 집계가 있는 종목(최대 maxSymbols개)을 골라, 외국인·기관 추정 순위 2번(순매수·순매도 정렬)과
     * 종목별 프로그램 매매를 조회해 맥락 관측으로 넣어요.
     * 받는 것: 없어요. 돌려주는 것: 없어요. 한 요청이 실패해도 개수만 세고 다음 요청을 계속해요.
     */
    @Scheduled(fixedDelayString = "${closing.trajectory.flow.interval:5m}", scheduler = "trajectoryContextScheduler")
    public synchronized void collect() {
        if (!active()) return;
        Instant now = calendar.now();
        Set<String> symbols = new LinkedHashSet<>(store.recentSymbols(now.minus(trajectory.contextMaxAge()), now, policy.maxSymbols()));
        if (symbols.isEmpty()) return;
        // Two ranked responses are a bounded sample; absent symbols remain missing, never zero.
        for (String direction : List.of("0", "1")) {
            if (!active()) return;
            try {
                JsonNode response = get("foreign-institution-total", "FHPTJ04400000", Map.of(
                        "FID_COND_MRKT_DIV_CODE", "V", "FID_COND_SCR_DIV_CODE", "16449",
                        "FID_INPUT_ISCD", "0000", "FID_DIV_CLS_CODE", "0",
                        "FID_RANK_SORT_CLS_CODE", direction, "FID_ETC_CLS_CODE", "0"));
                parseEstimates(response.path("output"), symbols, calendar.now()).forEach(service::context);
            } catch (RuntimeException error) { failed("ESTIMATE", error); }
        }
        for (String symbol : symbols) {
            if (!active()) return;
            try {
                JsonNode response = get("program-trade-by-stock", "FHPPG04650101",
                        Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", symbol));
                Context row = parseProgram(response.path("output"), symbol, calendar.now());
                if (row != null) service.context(row);
            } catch (RuntimeException error) { failed("PROGRAM", error); }
        }
        lastCompletedAt = calendar.now();
    }
    /** 하는 일: 수급·Trajectory·KIS가 켜져 있고, 실서버 주소이며, 거래일 09:00~분석 종료 시각 사이인지 확인해요. 돌려주는 것: 수집해도 되면 true예요. */
    private boolean active() {
        var now = calendar.now().atZone(ClosingTradingCalendar.ZONE);
        return policy.enabled() && trajectory.enabled() && kis.enabled()
                && kis.baseUrl() != null && "openapi.koreainvestment.com".equals(kis.baseUrl().getHost())
                && calendar.isTradingDay(now.toLocalDate())
                && !now.toLocalTime().isBefore(LocalTime.of(9, 0)) && !now.toLocalTime().isAfter(trajectory.analysisEnd());
    }
    /**
     * 하는 일: KIS 시세 조회 API를 공통 헤더(토큰·앱키·거래ID)로 호출하고, 실패 응답이면 예외로 바꿔요.
     * 받는 것: 엔드포인트 이름, 거래 ID(tr_id), 쿼리 파라미터예요. 돌려주는 것: 응답 JSON이에요.
     */
    private JsonNode get(String endpoint, String tr, Map<String, String> params) {
        kis.requireCredentials();
        return requests.execute(false, () -> {
            JsonNode response = client.get().uri(b -> {
                        b.path("/uapi/domestic-stock/v1/quotations/" + endpoint);
                        params.forEach(b::queryParam); return b.build();
                    }).header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", kis.appKey()).header("appsecret", kis.appSecret())
                    .header("tr_id", tr).header("custtype", "P").retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText()))
                KisResponseErrors.failure("KIS flow failed", response == null ? "EMPTY" : response.path("msg_cd").asText(), "Flow unavailable");
            return response;
        });
    }
    /**
     * 하는 일: 실패 횟수와 KIS 오류 코드별 횟수를 올리고, 종류·예외 이름·HTTP 상태·msg_cd·짧은 설명을 경고 로그로 남겨요.
     * 응답 본문의 오류 코드와 설명만 남기고 인증정보(토큰·앱키)는 남기지 않아요. 받는 것: 종류, 예외. 돌려주는 것: 없어요.
     */
    private void failed(String kind, RuntimeException error) {
        failures++;
        KisErrorDetail detail = KisErrorDetail.of(error);
        failureCodes.merge(kind + " " + detail.key(), 1L, Long::sum);
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Flow collection failed kind={} type={} {}", kind,
                error.getClass().getSimpleName(), detail.describe());
    }
    /** 하는 일: 설정, 실패 횟수, 마지막 조회 완료 시각을 돌려줘요. 완료는 "조회 시도 완료"일 뿐 최신 자료 보장이 아니에요. */
    public synchronized Map<String, Object> status() {
        return fields("enabled", policy.enabled(), "trajectoryEnabled", trajectory.enabled(),
                "maxSymbols", policy.maxSymbols(), "intervalSeconds", policy.interval().toSeconds(),
                "failures", failures, "failureCodes", new java.util.TreeMap<>(failureCodes), "lastCompletedAt", lastCompletedAt,
                "note", "Completion is a poll attempt, not proof of fresh provider data; live endpoint only");
    }
    /**
     * 하는 일: 외국인·기관 가집계 응답에서 대상 종목의 외국인(frgn_ntby_qty)·기관(orgn_ntby_qty) 순매수 수량을 꺼내요.
     * 원천시각은 알 수 없어 비워 두고 수신시각만 기록해요.
     * 받는 것: output 배열, 대상 종목 집합, 수신시각이에요. 돌려주는 것: FOREIGN/INSTITUTION_NET_BUY Context 목록이에요.
     */
    static List<Context> parseEstimates(JsonNode output, Set<String> symbols, Instant received) {
        if (!output.isArray()) throw new IllegalArgumentException("Expected estimate rows");
        List<Context> rows = new ArrayList<>();
        for (JsonNode row : output) {
            String symbol = row.path("mksc_shrn_iscd").asText();
            if (!symbols.contains(symbol)) continue;
            for (String party : List.of("FOREIGN", "INSTITUTION")) {
                BigDecimal value = number(row, party.equals("FOREIGN") ? "frgn_ntby_qty" : "orgn_ntby_qty");
                if (value != null) rows.add(new Context(party + "_NET_BUY", symbol, "KIS_ESTIMATE_ALL", received,
                        value, null, null, "ESTIMATED_SOURCE_TIME_UNKNOWN", "SHARES"));
            }
        }
        return rows;
    }
    /**
     * 하는 일: 종목별 프로그램 매매 응답에서 09:00~15:20 사이이고 수신시각보다 미래가 아닌 행 중 원천시각이 가장 늦은 순매수 수량을 골라요.
     * 원천시각 날짜는 요청(수신) 날짜를 써요. 받는 것: output 배열, 종목, 수신시각이에요. 돌려주는 것: PROGRAM_NET_BUY Context 또는 null이에요.
     */
    static Context parseProgram(JsonNode output, String symbol, Instant received) {
        if (!output.isArray()) throw new IllegalArgumentException("Expected program rows");
        Context latest = null;
        for (JsonNode row : output) {
            String time = row.path("bsop_hour").asText();
            BigDecimal value = number(row, "whol_smtn_ntby_qty");
            if (value == null || !time.matches("[0-9]{6}")) continue;
            LocalTime local = LocalTime.parse(time, DateTimeFormatter.ofPattern("HHmmss"));
            Instant source = received.atZone(ClosingTradingCalendar.ZONE).toLocalDate().atTime(local)
                    .atZone(ClosingTradingCalendar.ZONE).toInstant();
            if (local.isBefore(LocalTime.of(9, 0)) || local.isAfter(LocalTime.of(15, 20)) || source.isAfter(received)) continue;
            if (latest == null || source.isAfter(latest.sourceAt()))
                latest = new Context("PROGRAM_NET_BUY", symbol, "KRX", received, value, null, source,
                        "INTRADAY_PROVISIONAL_DATE_FROM_REQUEST", "SHARES");
        }
        return latest;
    }
    /** 하는 일: 응답 필드 문자열을 쉼표를 빼고 숫자로 바꿔요. 받는 것: 노드, 필드 이름. 돌려주는 것: 숫자 또는 null(빈 값)이에요. */
    private static BigDecimal number(JsonNode row, String name) {
        String value = row.path(name).asText("").trim().replace(",", "");
        return value.isEmpty() ? null : new BigDecimal(value);
    }
}
