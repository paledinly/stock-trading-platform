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
    private Instant lastCompletedAt;

    public InvestorFlowCollector(InvestorFlowProperties policy, TrajectoryProperties trajectory,
            TrajectoryStore store, TrajectoryService service, RestClient kisRestClient, KisProperties kis,
            KisTokenManager tokens, KisRequestExecutor requests, ClosingTradingCalendar calendar) {
        this.policy = policy; this.trajectory = trajectory; this.store = store; this.service = service;
        client = kisRestClient; this.kis = kis; this.tokens = tokens; this.requests = requests; this.calendar = calendar;
    }

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
    private boolean active() {
        var now = calendar.now().atZone(ClosingTradingCalendar.ZONE);
        return policy.enabled() && trajectory.enabled() && kis.enabled()
                && kis.baseUrl() != null && "openapi.koreainvestment.com".equals(kis.baseUrl().getHost())
                && calendar.isTradingDay(now.toLocalDate())
                && !now.toLocalTime().isBefore(LocalTime.of(9, 0)) && !now.toLocalTime().isAfter(trajectory.analysisEnd());
    }
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
    private void failed(String kind, RuntimeException error) {
        failures++;
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Flow collection failed kind={} type={}", kind, error.getClass().getSimpleName());
    }
    public synchronized Map<String, Object> status() {
        return fields("enabled", policy.enabled(), "trajectoryEnabled", trajectory.enabled(),
                "maxSymbols", policy.maxSymbols(), "intervalSeconds", policy.interval().toSeconds(),
                "failures", failures, "lastCompletedAt", lastCompletedAt,
                "note", "Completion is a poll attempt, not proof of fresh provider data; live endpoint only");
    }
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
    private static BigDecimal number(JsonNode row, String name) {
        String value = row.path(name).asText("").trim().replace(",", "");
        return value.isEmpty() ? null : new BigDecimal(value);
    }
}
