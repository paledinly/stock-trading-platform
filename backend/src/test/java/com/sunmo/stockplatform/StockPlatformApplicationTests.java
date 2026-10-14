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

    /** 애플리케이션 설정(빈 구성)이 오류 없이 올라오는지만 확인해요. */
    @Test
    void contextLoads() {
    }
}
