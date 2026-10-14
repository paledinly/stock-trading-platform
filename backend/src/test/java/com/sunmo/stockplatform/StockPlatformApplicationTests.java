/*
 * 스프링 애플리케이션 전체 설정이 뜨는지, 그리고 가벼운 조회 쿼리가 실제 DB 위에서 동작하는지 확인해요.
 * H2(테스트용 메모리 DB)를 PostgreSQL 호환 모드로 쓰고, Redis와 실시간 연결은 끈 채로 실행해요.
 */
package com.sunmo.stockplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 스프링 컨텍스트 로딩과 저장소 쿼리를 점검하는 통합 테스트예요. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "market.realtime.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        })
class StockPlatformApplicationTests {
    @org.springframework.beans.factory.annotation.Autowired
    com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    com.sunmo.stockplatform.stock.infrastructure.StockRepository stocks;
    @org.springframework.beans.factory.annotation.Autowired
    com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository performances;
    @org.springframework.beans.factory.annotation.Autowired
    com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository closingRuns;
    @org.springframework.beans.factory.annotation.Autowired
    com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository closingRecommendations;
    @org.springframework.beans.factory.annotation.Autowired
    com.sunmo.stockplatform.closing.infrastructure.OvernightPerformanceRepository overnightPerformances;
    @org.springframework.beans.factory.annotation.Autowired
    com.sunmo.stockplatform.closing.infrastructure.OvernightPositionDecisionRepository overnightDecisions;

    /**
     * 최신 스냅샷만 고르는 커버리지 쿼리와, 설정 ID가 null인 성과 조회가 동작하는지 확인해요.
     * 준비: 같은 종목·같은 날에 05:00 실패 스냅샷과 05:02 수집 성공 스냅샷을 저장해요.
     * 기대: 최신 커버리지는 1건이고 COLLECTED 상태이며, null 필터 성과 조회는 빈 목록이에요.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void lightweightQueriesSelectLatestSnapshotAndAcceptNullableSettingFilter() {
        // 준비
        var stock = stocks.save(new com.sunmo.stockplatform.stock.domain.Stock("987654", "KR7987654003", "fixture",
                com.sunmo.stockplatform.stock.domain.Market.KOSPI,
                com.sunmo.stockplatform.stock.domain.MarketType.STOCK, false, false, java.time.Instant.now()));
        var date = java.time.LocalDate.of(2026, 9, 14);
        var first = new com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot(date,
                java.time.Instant.parse("2026-09-14T05:00:00Z"), stock);
        first.updateData(null, java.math.BigDecimal.ZERO, "{}", null, "failed");
        var second = new com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot(date,
                java.time.Instant.parse("2026-09-14T05:02:00Z"), stock);
        var at = java.time.Instant.parse("2026-09-14T05:02:00Z");
        second.updateData(new com.sunmo.stockplatform.marketwide.domain.BroadQuoteData(java.math.BigDecimal.TEN,
                java.math.BigDecimal.ZERO, 10L, java.math.BigDecimal.TEN, null, null, null, null, at, "REST"),
                java.math.BigDecimal.TEN, "{}", null, null);
        snapshots.saveAllAndFlush(java.util.List.of(first, second));
        // 확인
        org.assertj.core.api.Assertions.assertThat(snapshots.findLatestCoverage(date)).hasSize(1)
                .first().satisfies(row -> org.assertj.core.api.Assertions.assertThat(row.getCollectionStatus())
                        .isEqualTo(com.sunmo.stockplatform.marketwide.domain.BroadSnapshotStatus.COLLECTED));
        org.assertj.core.api.Assertions.assertThat(performances.findAnalyticsRows(null, at, at.plusSeconds(1))).isEmpty();
    }

    /**
     * 날짜별 다음날 성과·보유 판단 조회가 추천 조회와 같은 대표 실행(그날 최신 FORWARD, 없으면 최신 실행)을 보는지,
     * 그리고 평가시각이 같은 판단이 여러 개여도 추천마다 한 줄(ID가 가장 큰 것)만 나오는지 확인해요.
     * 준비: 같은 날 FORWARD 실행 뒤에 REPLAY 실행을 하나 더 만들고(그래서 REPLAY의 ID가 더 커요), 각각 추천·성과·판단을 붙여요.
     * FORWARD 추천에는 같은 평가시각 판단 2건과 더 이른 판단 1건을 넣어요. 다른 날에는 REPLAY 실행만 있어요.
     * 기대: 추천·성과·판단 모두 FORWARD 실행의 것이고 판단은 1줄(나중 저장분)이며, FORWARD가 없는 날은 최신 실행을 봐요.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void overnightQueriesFollowTheRepresentativeRunAndReturnOneDecisionPerRecommendation() {
        var stock = stocks.save(new com.sunmo.stockplatform.stock.domain.Stock("987655", "KR7987655000", "fixture",
                com.sunmo.stockplatform.stock.domain.Market.KOSPI,
                com.sunmo.stockplatform.stock.domain.MarketType.STOCK, false, false, java.time.Instant.now()));
        var date = java.time.LocalDate.of(2026, 9, 15);
        var otherDate = java.time.LocalDate.of(2026, 9, 16);
        var at = java.time.Instant.parse("2026-09-15T06:15:00Z");
        var snapshot = new com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot(date, at, stock);
        snapshot.updateData(new com.sunmo.stockplatform.marketwide.domain.BroadQuoteData(java.math.BigDecimal.TEN,
                java.math.BigDecimal.ZERO, 10L, java.math.BigDecimal.TEN, null, null, null, null, at, "REST"),
                java.math.BigDecimal.TEN, "{}", null, null);
        snapshots.saveAndFlush(snapshot);
        var forward = closingRuns.saveAndFlush(new com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun(
                date, at, "test", "FORWARD", at, "{}", "hash", "official-test-forward"));
        var replay = closingRuns.saveAndFlush(new com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun(
                date, at.plusSeconds(60), "test", "REPLAY", at, "{}", "hash", "test-replay"));
        var otherReplay = closingRuns.saveAndFlush(new com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun(
                otherDate, at.plusSeconds(86400), "test", "REPLAY", at, "{}", "hash", "test-other-replay"));
        var forwardRec = recommendation(date, at, snapshot, forward);
        var replayRec = recommendation(date, at, snapshot, replay);
        var otherRec = recommendation(otherDate, at, snapshot, otherReplay);
        overnightPerformances.saveAndFlush(new com.sunmo.stockplatform.closing.domain.OvernightPerformance(forwardRec));
        overnightPerformances.saveAndFlush(new com.sunmo.stockplatform.closing.domain.OvernightPerformance(replayRec));
        overnightPerformances.saveAndFlush(new com.sunmo.stockplatform.closing.domain.OvernightPerformance(otherRec));
        var latestAt = java.time.Instant.parse("2026-09-16T01:00:00Z");
        decision(forwardRec, latestAt.minusSeconds(600));
        decision(forwardRec, latestAt);
        var lastSaved = decision(forwardRec, latestAt);
        decision(replayRec, latestAt.plusSeconds(60));

        org.assertj.core.api.Assertions.assertThat(closingRecommendations.findByRecommendationDateOrderByRankAsc(date))
                .extracting(com.sunmo.stockplatform.closing.domain.ClosingRecommendation::getId)
                .containsExactly(forwardRec.getId());
        org.assertj.core.api.Assertions.assertThat(overnightPerformances.findByRecommendationDate(date))
                .extracting(row -> row.getRecommendation().getId()).containsExactly(forwardRec.getId());
        org.assertj.core.api.Assertions.assertThat(overnightDecisions.findLatestByRecommendationDate(date))
                .extracting(com.sunmo.stockplatform.closing.domain.OvernightPositionDecision::getId)
                .containsExactly(lastSaved.getId());
        org.assertj.core.api.Assertions.assertThat(overnightPerformances.findByRecommendationDate(otherDate))
                .extracting(row -> row.getRecommendation().getId()).containsExactly(otherRec.getId());
    }

    /** 실행에 연결된 1순위 Broad 추천을 저장해요. */
    private com.sunmo.stockplatform.closing.domain.ClosingRecommendation recommendation(java.time.LocalDate date,
            java.time.Instant at, com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot snapshot,
            com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun run) {
        var recommendation = new com.sunmo.stockplatform.closing.domain.ClosingRecommendation(date, at, snapshot, 1,
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, "{}", "{}", "[]");
        recommendation.assignRun(run);
        return closingRecommendations.saveAndFlush(recommendation);
    }

    /** 주어진 평가시각의 HOLD 판단을 저장해요. */
    private com.sunmo.stockplatform.closing.domain.OvernightPositionDecision decision(
            com.sunmo.stockplatform.closing.domain.ClosingRecommendation recommendation, java.time.Instant at) {
        return overnightDecisions.saveAndFlush(new com.sunmo.stockplatform.closing.domain.OvernightPositionDecision(
                recommendation, at, null, null, null, null, null, null, null, null, false, false,
                com.sunmo.stockplatform.closing.domain.OvernightPositionDecisionType.HOLD, "{}"));
    }

    /** 애플리케이션 설정(빈 구성)이 오류 없이 올라오는지만 확인해요. */
    @Test
    void contextLoads() {
    }
}
