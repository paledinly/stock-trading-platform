/*
 * Trajectory 스냅샷에 쓸 "주변 상황" 자료를 모으는 수집기예요. 거래대금 순위 수신 이벤트를 순위 관측으로 바꾸고,
 * 5분마다 KIS REST API(한국투자증권 시세 서버)로 KOSPI·KOSDAQ 지수를 받아 TrajectoryService 대기열에 넣어요.
 * 수집이 켜져 있을 때만 동작하고, 지수 수집은 indexEnabled도 켜져 있어야 해요. REST 응답에는 원천시각이 없어 수신시각만 저장해요.
 */
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

/**
 * 순위와 지수 같은 시장 맥락을 모아 Trajectory 서비스에 넘기는 수집기예요.
 * 일기 쓰는 사람 옆에서 "지금 반 전체 분위기(지수)와 내 등수(순위)"를 받아 적어 주는 보조 기록원이라고 보면 돼요.
 */
@Component
public class TrajectoryContextCollector {
    private final TrajectoryProperties policy;
    private final TrajectoryService service;
    private final RestClient client;
    private final KisProperties kis;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;
    private final ClosingTradingCalendar calendar;
    /** 하는 일: 설정·서비스·KIS 접속 도구·달력을 받아 보관해요. 받는 것: 각 의존 객체. 돌려주는 것: 수집기 객체예요. */
    public TrajectoryContextCollector(TrajectoryProperties policy, TrajectoryService service, RestClient kisRestClient,
            KisProperties kis, KisTokenManager tokens, KisRequestExecutor requests, ClosingTradingCalendar calendar) {
        this.policy = policy; this.service = service; client = kisRestClient; this.kis = kis;
        this.tokens = tokens; this.requests = requests; this.calendar = calendar;
    }
    /**
     * 하는 일: 거래대금 순위 수신 이벤트를 받아 종목별 TURNOVER_RANK 관측으로 바꿔 넣어요. 시장 구분이 없으면 범위를 ALL로 둬요.
     * 받는 것: ObservedRanking 이벤트예요. 다른 순위 종류는 무시해요. 돌려주는 것: 없어요.
     */
    @EventListener
    public void ranking(ObservedRanking event) {
        if (!policy.enabled() || event.type() != RankingType.TURNOVER) return;
        String scope = event.market() == null ? "ALL" : event.market().name();
        for (var entry : event.entries()) service.context(new Context("TURNOVER_RANK", entry.stockCode(), scope,
                event.receivedAt(), BigDecimal.valueOf(entry.rank()), null));
    }
    /**
     * 하는 일: (기본 5분마다) 거래일 09:00~분석 종료 시각 사이에 KOSPI(0001)·KOSDAQ(1001) 지수를 수집해요.
     * 받는 것: 없어요. 돌려주는 것: 없어요. 수집·지수·KIS 중 하나라도 꺼져 있으면 아무것도 안 해요.
     */
    @Scheduled(fixedDelayString = "${closing.trajectory.index-interval:5m}", scheduler = "trajectoryContextScheduler")
    public void indices() {
        var now = calendar.now().atZone(ClosingTradingCalendar.ZONE);
        if (!policy.enabled() || !policy.indexEnabled() || !kis.enabled() || !calendar.isTradingDay(now.toLocalDate())
                || now.toLocalTime().isBefore(LocalTime.of(9, 0)) || now.toLocalTime().isAfter(policy.analysisEnd())) return;
        collectIndex("KOSPI", "0001"); collectIndex("KOSDAQ", "1001");
    }
    /**
     * 하는 일: KIS 지수 현재가 API(FHPUP02100000)를 불러 지수 관측을 만들어 넣어요. 실패하면 경고 로그만 남기고 넘어가요.
     * 받는 것: 지수 이름(KOSPI/KOSDAQ), 업종 코드예요. 돌려주는 것: 없어요. 인증정보는 로그에 남기지 않아요.
     */
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
    /**
     * 하는 일: KIS 응답의 지수 값(bstp_nmix_prpr)과 전일 대비 등락률(bstp_nmix_prdy_ctrt)을 꺼내 INDEX 관측으로 만들어요.
     * 받는 것: 지수 이름, 응답 output 노드, 수신시각이에요. 돌려주는 것: Context예요. 값이 0 이하면 예외를 던져요.
     */
    static Context parseIndex(String name, JsonNode output, Instant receivedAt) {
        BigDecimal value = new BigDecimal(output.path("bstp_nmix_prpr").asText().replace(",", ""));
        BigDecimal change = new BigDecimal(output.path("bstp_nmix_prdy_ctrt").asText().replace(",", ""));
        if (value.signum() <= 0) throw new IllegalArgumentException("Invalid index");
        return new Context("INDEX", name, name, receivedAt, value, change);
    }
}
