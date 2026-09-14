package com.sunmo.stockplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
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

    @Test
    @org.springframework.transaction.annotation.Transactional
    void lightweightQueriesSelectLatestSnapshotAndAcceptNullableSettingFilter() {
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
        org.assertj.core.api.Assertions.assertThat(snapshots.findLatestCoverage(date)).hasSize(1)
                .first().satisfies(row -> org.assertj.core.api.Assertions.assertThat(row.getCollectionStatus())
                        .isEqualTo(com.sunmo.stockplatform.marketwide.domain.BroadSnapshotStatus.COLLECTED));
        org.assertj.core.api.Assertions.assertThat(performances.findAnalyticsRows(null, at, at.plusSeconds(1))).isEmpty();
    }

    @Test
    void contextLoads() {
    }
}
