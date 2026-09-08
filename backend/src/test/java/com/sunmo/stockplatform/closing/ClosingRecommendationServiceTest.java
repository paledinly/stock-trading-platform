package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.domain.Market;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClosingRecommendationServiceTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void precisionCandidateWinsWhenSameStockAlsoHasBroadSnapshot() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        Stock stock = fixture.stock(1L);
        ScannerDetection detection = fixture.detection(stock, date.atTime(15, 0).atZone(SEOUL).toInstant());
        MarketBroadSnapshot broad = fixture.snapshot(stock, date.atTime(15, 10).atZone(SEOUL).toInstant(), BroadSnapshotQuality.BROAD_C);
        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of(detection));
        when(fixture.snapshots.findClosingCandidates(eq(date), any(), any())).thenReturn(List.of(broad));

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().getFirst().candidateSource()).isEqualTo("PRECISION");
    }

    @Test
    void includesBroadOnlyCandidateAndExcludesInsufficientSnapshot() {
        Fixture fixture = new Fixture();
        LocalDate date = LocalDate.now(SEOUL).minusDays(1);
        MarketBroadSnapshot valid = fixture.snapshot(fixture.stock(1L), date.atTime(15, 0).atZone(SEOUL).toInstant(), BroadSnapshotQuality.BROAD_C);
        MarketBroadSnapshot insufficient = fixture.snapshot(fixture.stock(2L), date.atTime(15, 1).atZone(SEOUL).toInstant(), BroadSnapshotQuality.INSUFFICIENT);

        when(fixture.detections.findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(eq(date), any()))
                .thenReturn(List.of());
        when(fixture.snapshots.findClosingCandidates(eq(date), any(), any())).thenReturn(List.of(insufficient, valid));

        var response = fixture.service.generate(date, 10, bd("35"), bd("65"));

        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().getFirst().candidateSource()).isEqualTo("BROAD");
        assertThat(response.candidates().getFirst().recommendationScore()).isLessThanOrEqualTo(bd("65"));
        assertThat(response.candidates().getFirst().missingFeatures()).contains("vwap", "volumeRatio");
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    private static final class Fixture {
        final ScannerDetectionRepository detections = mock(ScannerDetectionRepository.class);
        final ClosingRecommendationRepository recommendations = mock(ClosingRecommendationRepository.class);
        final ClosingRecommendationScorer precisionScorer = mock(ClosingRecommendationScorer.class);
        final IntradayMovingAverageService intraday = mock(IntradayMovingAverageService.class);
        final DailyMovingAverageService daily = mock(DailyMovingAverageService.class);
        final MarketBroadSnapshotRepository snapshots = mock(MarketBroadSnapshotRepository.class);
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final ClosingRecommendationService service;

        Fixture() {
            when(recommendations.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
            when(precisionScorer.score(any(), any(), any())).thenReturn(new ScoreResult(bd("80"), "{}", "{}"));
            service = new ClosingRecommendationService(detections, recommendations, precisionScorer, intraday, daily,
                    snapshots, new BroadClosingRecommendationScorer(mapper), mapper);
        }

        Stock stock(long id) {
            Stock stock = mock(Stock.class);
            when(stock.getId()).thenReturn(id);
            when(stock.getStockCode()).thenReturn("00000" + id);
            when(stock.getStockName()).thenReturn("stock" + id);
            when(stock.getMarket()).thenReturn(Market.KOSPI);
            when(stock.isActive()).thenReturn(true);
            return stock;
        }

        ScannerDetection detection(Stock stock, Instant at) {
            ScannerDetection detection = mock(ScannerDetection.class);
            when(detection.getStock()).thenReturn(stock);
            when(detection.getDetectedAt()).thenReturn(at);
            when(detection.getDetectedPrice()).thenReturn(bd("10000"));
            when(detection.getOpportunityScore()).thenReturn(bd("75"));
            when(detection.getRiskScore()).thenReturn(bd("20"));
            when(detection.getVolumeRatio()).thenReturn(bd("2"));
            when(detection.getType()).thenReturn(ScannerType.VWAP_BREAKOUT);
            when(detection.getFeatureSnapshot()).thenReturn("{\"vwapDistanceRate\":1,\"dayHighDistanceRate\":1,\"tradeStrength\":120}");
            return detection;
        }

        MarketBroadSnapshot snapshot(Stock stock, Instant at, BroadSnapshotQuality quality) {
            MarketBroadSnapshot snapshot = mock(MarketBroadSnapshot.class);
            Long stockId = stock.getId();
            when(snapshot.getId()).thenReturn(stockId);
            when(snapshot.getStock()).thenReturn(stock);
            when(snapshot.getCapturedAt()).thenReturn(at);
            when(snapshot.getDataQuality()).thenReturn(quality);
            when(snapshot.getCollectionStatus()).thenReturn(quality == BroadSnapshotQuality.BROAD_C
                    ? BroadSnapshotStatus.COLLECTED : BroadSnapshotStatus.QUOTE_FAILED);
            when(snapshot.getCurrentPrice()).thenReturn(bd("10000"));
            when(snapshot.getAccumulatedTradingValue()).thenReturn(bd("5000000000"));
            when(snapshot.getChangeRate()).thenReturn(bd("2"));
            when(snapshot.getBroadScore()).thenReturn(bd("80"));
            when(snapshot.getRankingSources()).thenReturn("[\"TURNOVER\"]");
            return snapshot;
        }
    }
}
