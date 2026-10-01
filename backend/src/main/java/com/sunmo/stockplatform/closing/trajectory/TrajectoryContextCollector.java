package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.*;
import com.sunmo.stockplatform.kis.ranking.*;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

@Component
public class TrajectoryContextCollector {
    private final TrajectoryProperties policy;
    private final TrajectoryService service;
    private final RestClient client;
    private final KisProperties kis;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;
    private final ClosingTradingCalendar calendar;
    public TrajectoryContextCollector(TrajectoryProperties policy, TrajectoryService service, RestClient kisRestClient,
            KisProperties kis, KisTokenManager tokens, KisRequestExecutor requests, ClosingTradingCalendar calendar) {
        this.policy = policy; this.service = service; client = kisRestClient; this.kis = kis;
        this.tokens = tokens; this.requests = requests; this.calendar = calendar;
    }
    @EventListener
    public void ranking(ObservedRanking event) {
        if (!policy.enabled() || event.type() != RankingType.TURNOVER) return;
        String scope = event.market() == null ? "ALL" : event.market().name();
        for (var entry : event.entries()) service.context(new Context("TURNOVER_RANK", entry.stockCode(), scope,
                event.receivedAt(), BigDecimal.valueOf(entry.rank()), null));
    }
    @Scheduled(fixedDelayString = "${closing.trajectory.index-interval:5m}", scheduler = "trajectoryContextScheduler")
    public void indices() {
        var now = calendar.now().atZone(ClosingTradingCalendar.ZONE);
        if (!policy.enabled() || !policy.indexEnabled() || !kis.enabled() || !calendar.isTradingDay(now.toLocalDate())
                || now.toLocalTime().isBefore(LocalTime.of(9, 0)) || now.toLocalTime().isAfter(policy.analysisEnd())) return;
        collectIndex("KOSPI", "0001"); collectIndex("KOSDAQ", "1001");
    }
    void collectIndex(String name, String code) {
        try {
            kis.requireCredentials();
            Context observation = requests.execute(false, () -> {
                JsonNode response = client.get().uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-index-price")
                                .queryParam("FID_COND_MRKT_DIV_CODE", "U").queryParam("FID_INPUT_ISCD", code).build())
                        .header("authorization", "Bearer " + tokens.getAccessToken()).header("appkey", kis.appKey())
                        .header("appsecret", kis.appSecret()).header("tr_id", "FHPUP02100000").header("custtype", "P")
                        .retrieve().body(JsonNode.class);
                if (response == null || !"0".equals(response.path("rt_cd").asText()))
                    KisResponseErrors.failure("KIS index failed", response == null ? "EMPTY" : response.path("msg_cd").asText(), "Index unavailable");
                return parseIndex(name, response.path("output"), calendar.now());
            });
            service.context(observation);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Index observation unavailable market={} type={}", name, failure.getClass().getSimpleName());
        }
    }
    static Context parseIndex(String name, JsonNode output, Instant receivedAt) {
        BigDecimal value = new BigDecimal(output.path("bstp_nmix_prpr").asText().replace(",", ""));
        BigDecimal change = new BigDecimal(output.path("bstp_nmix_prdy_ctrt").asText().replace(",", ""));
        if (value.signum() <= 0) throw new IllegalArgumentException("Invalid index");
        return new Context("INDEX", name, name, receivedAt, value, change);
    }
}
