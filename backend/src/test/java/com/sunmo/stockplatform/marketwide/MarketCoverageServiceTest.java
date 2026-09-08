package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.marketwide.application.MarketCoverageService;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.marketwide.infrastructure.*;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketCoverageServiceTest {
    @Test
    void calculatesCoverageSubscriptionResidenceAndSourcePerformance() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        Stock stock1 = stock(1L, true);
        Stock stock2 = stock(2L, true);
        MarketBroadSnapshot collected = snapshot(stock1, BroadSnapshotQuality.BROAD_C, BroadSnapshotStatus.COLLECTED);
        MarketBroadSnapshot failed = snapshot(stock2, BroadSnapshotQuality.INSUFFICIENT, BroadSnapshotStatus.QUOTE_FAILED);
        PrecisionSubscriptionSession session = new PrecisionSubscriptionSession("000001",
                date.atTime(14, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant(), true);
        session.end(date.atTime(15, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(), "REPLACED");

        ClosingRecommendation recommendation = mock(ClosingRecommendation.class);
        when(recommendation.getId()).thenReturn(10L);
        when(recommendation.getStock()).thenReturn(stock1);
        when(recommendation.getCandidateSource()).thenReturn(ClosingCandidateSource.PRECISION);
        OvernightPerformance performance = mock(OvernightPerformance.class);
        when(performance.getRecommendation()).thenReturn(recommendation);
        when(performance.getStatus()).thenReturn(OvernightPerformanceStatus.COMPLETED);
        when(performance.getOpenReturnRate()).thenReturn(bd("1"));
        when(performance.getCloseReturnRate()).thenReturn(bd("2"));
        when(performance.getMaxReturnRate()).thenReturn(bd("3"));
        when(performance.getMaxDrawdownRate()).thenReturn(bd("-1"));
        when(performance.isTargetHit()).thenReturn(true);

        StockRepository stocks = mock(StockRepository.class);
        MarketBroadSnapshotRepository snapshots = mock(MarketBroadSnapshotRepository.class);
        PrecisionSubscriptionSessionRepository sessions = mock(PrecisionSubscriptionSessionRepository.class);
        ScannerDetectionRepository detections = mock(ScannerDetectionRepository.class);
        ClosingRecommendationRepository recommendations = mock(ClosingRecommendationRepository.class);
        OvernightPerformanceRepository performances = mock(OvernightPerformanceRepository.class);
        MarketWideScanRunRepository runs = mock(MarketWideScanRunRepository.class);
        when(stocks.countByActiveTrue()).thenReturn(100L);
        when(stocks.countByActiveTrueAndManagedFalseAndTradingHaltedFalseAndEtfFalseAndEtnFalse()).thenReturn(80L);
        when(snapshots.findBySessionDateOrderByCapturedAtAsc(date)).thenReturn(List.of(collected, failed));
        when(sessions.findBySessionDateOrderByRequestedAtAsc(date)).thenReturn(List.of(session));
        when(recommendations.findByRecommendationDateOrderByRankAsc(date)).thenReturn(List.of(recommendation));
        when(performances.findByRecommendationDate(date)).thenReturn(List.of(performance));
        when(runs.findBySessionDateOrderByScheduledForAsc(date)).thenReturn(List.of());
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of());

        var result = new MarketCoverageService(stocks, snapshots, sessions, detections, recommendations,
                performances, runs).coverage(date);

        assertThat(result.rankingCoverageRate()).isEqualByComparingTo("2.500");
        assertThat(result.broadCoverageRate()).isEqualByComparingTo("1.250");
        assertThat(result.averagePrecisionMinutes()).isEqualByComparingTo("30.000");
        assertThat(result.precisionRecommendations()).isEqualTo(1);
        assertThat(result.sourcePerformance()).filteredOn(row -> row.source().equals("PRECISION"))
                .singleElement().satisfies(row -> {
                    assertThat(row.closeWinRate()).isEqualByComparingTo("100.000");
                    assertThat(row.averageCloseReturn()).isEqualByComparingTo("2.000000");
                });
        assertThat(result.exclusionReasons()).containsEntry("PROMOTED_TO_PRECISION", 1)
                .containsEntry("QUOTE_FAILED", 1);
    }

    private static Stock stock(long id, boolean active) {
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(id);
        when(stock.isActive()).thenReturn(active);
        return stock;
    }
    private static MarketBroadSnapshot snapshot(Stock stock, BroadSnapshotQuality quality, BroadSnapshotStatus status) {
        MarketBroadSnapshot snapshot = mock(MarketBroadSnapshot.class);
        when(snapshot.getStock()).thenReturn(stock);
        when(snapshot.getDataQuality()).thenReturn(quality);
        when(snapshot.getCollectionStatus()).thenReturn(status);
        return snapshot;
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
