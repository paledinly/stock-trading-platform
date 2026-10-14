/*
 * MarketCoverageService가 하루 동안 전시장 수집이 얼마나 넓게 됐는지(커버리지), 정밀 구독이 얼마나 유지됐는지,
 * 후보 출처별 다음 날 성과가 어땠는지를 계산하는지 확인해요.
 * 수집 범위와 출처별 성과를 함께 봐야 전략 검증 결과를 해석할 수 있어서 중요해요.
 */
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

/** 전시장 커버리지·출처별 성과 집계를 점검하는 테스트예요. */
class MarketCoverageServiceTest {
    /**
     * 커버리지 비율, 정밀 구독 유지 시간, 출처별 성과, 제외 사유 집계를 확인해요.
     * 준비: 거래 가능 종목 80개, 수집 성공 1건·실패 1건, 14:30~15:00 정밀 구독 1건, PRECISION 출처 추천 1건(종가 수익률 2%)을 목(mock, 가짜 객체)으로 준비해요.
     * 기대: 순위 커버리지 2.5%, 전시장 커버리지 1.25%, 평균 정밀 구독 30분, PRECISION 종가 승률 100%·평균 2%, 제외 사유 PROMOTED_TO_PRECISION 1·QUOTE_FAILED 1이에요.
     */
    @Test
    void calculatesCoverageSubscriptionResidenceAndSourcePerformance() {
        // 준비
        LocalDate date = LocalDate.of(2026, 9, 4);
        Stock stock1 = stock(1L, true);
        Stock stock2 = stock(2L, true);
        var collected = coverageRow(1L, BroadSnapshotQuality.BROAD_C, BroadSnapshotStatus.COLLECTED);
        var failed = coverageRow(2L, BroadSnapshotQuality.INSUFFICIENT, BroadSnapshotStatus.QUOTE_FAILED);
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
        when(snapshots.findLatestCoverage(date)).thenReturn(List.of(collected, failed));
        when(sessions.findBySessionDateOrderByRequestedAtAsc(date)).thenReturn(List.of(session));
        when(recommendations.findByRecommendationDateOrderByRankAsc(date)).thenReturn(List.of(recommendation));
        when(performances.findByRecommendationDate(date)).thenReturn(List.of(performance));
        when(runs.findBySessionDateOrderByScheduledForAsc(date)).thenReturn(List.of());
        when(detections.findByDetectedAtBetweenOrderByDetectedAtAsc(any(), any())).thenReturn(List.of());

        // 실행
        var result = new MarketCoverageService(stocks, snapshots, sessions, detections, recommendations,
                performances, runs).coverage(date);

        // 확인
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

    /** 종목 id·품질·수집 상태만 정한 커버리지 행 목을 만드는 도우미예요. */
    private static BroadCoverageRow coverageRow(long id, BroadSnapshotQuality quality, BroadSnapshotStatus status) {
        var row = mock(BroadCoverageRow.class);
        when(row.getStockId()).thenReturn(id);
        when(row.getDataQuality()).thenReturn(quality);
        when(row.getCollectionStatus()).thenReturn(status);
        when(row.getActive()).thenReturn(true);
        return row;
    }

    /** id와 활성 여부만 정한 종목 목을 만드는 도우미예요. */
    private static Stock stock(long id, boolean active) {
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(id);
        when(stock.isActive()).thenReturn(active);
        return stock;
    }
    /** 스냅샷 목을 만드는 도우미예요(현재 이 파일의 테스트에서는 쓰이지 않는 것으로 보여요). */
    private static MarketBroadSnapshot snapshot(Stock stock, BroadSnapshotQuality quality, BroadSnapshotStatus status) {
        MarketBroadSnapshot snapshot = mock(MarketBroadSnapshot.class);
        when(snapshot.getStock()).thenReturn(stock);
        when(snapshot.getDataQuality()).thenReturn(quality);
        when(snapshot.getCollectionStatus()).thenReturn(status);
        return snapshot;
    }
    /** 문자열을 BigDecimal로 바꾸는 도우미예요. */
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
