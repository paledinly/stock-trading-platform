package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.*;
import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClosingCandidateObservationServiceTest {
    @Test
    void tracksUnselectedForwardCandidateWithSameExecutionModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LocalDate recommendationDate = LocalDate.of(2026, 9, 29);
        LocalDate nextDate = LocalDate.of(2026, 9, 30);
        Instant completedAt = Instant.parse("2026-09-29T06:01:00Z");
        CandidateEvaluationResponse candidate = new CandidateEvaluationResponse(
                "005930", "삼성전자", "PRECISION", "VOLUME", completedAt.minusSeconds(120),
                new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("45"),
                new BigDecimal("30"), "PRECISION_A", 4, 20, List.of(), "EXCLUDED",
                "OPPORTUNITY_OR_RISK_FILTERED", "{}", "{}", null, Map.of());
        GenerateResponse response = new GenerateResponse(recommendationDate, completedAt.minusSeconds(10),
                1, 0, 0, 0, 1, Map.of("OPPORTUNITY_OR_RISK_FILTERED", 1), "test-v1", List.of(),
                completedAt.minusSeconds(60), Map.of(), Map.of(), List.of(candidate), null, "FORWARD", completedAt);
        ClosingRecommendationRun run = new ClosingRecommendationRun(recommendationDate,
                completedAt.minusSeconds(10), "test-v1", "FORWARD", completedAt.minusSeconds(60),
                "{}", "hash", "key");
        run.complete(mapper.writeValueAsString(response), completedAt);

        var runs = mock(ClosingRecommendationRunRepository.class);
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");
        when(stock.getStockName()).thenReturn("삼성전자");
        var stocks = mock(StockRepository.class);
        when(stocks.findByStockCodeIn(anyCollection())).thenReturn(List.of(stock));
        Instant nextOpen = Instant.parse("2026-09-30T00:00:00Z");
        StockCandle entry = candle(stock, Instant.parse("2026-09-29T06:05:00Z"));
        List<StockCandle> exits = IntStream.range(0, 78)
                .mapToObj(index -> candle(stock, nextOpen.plusSeconds(index * 300L))).toList();
        var candles = mock(StockCandleRepository.class);
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any())).thenReturn(List.of(entry), exits);
        var calendar = mock(ClosingTradingCalendar.class);
        when(calendar.nextTradingDay(recommendationDate)).thenReturn(nextDate);
        when(calendar.close(recommendationDate)).thenReturn(Instant.parse("2026-09-29T06:20:00Z"));
        when(calendar.open(nextDate)).thenReturn(nextOpen);
        when(calendar.close(nextDate)).thenReturn(Instant.parse("2026-09-30T06:30:00Z"));
        when(calendar.now()).thenReturn(Instant.parse("2026-09-30T06:31:00Z"));
        var observations = mock(ClosingCandidateObservationRepository.class);
        when(observations.findByRunIdAndStockIdAndCandidateSource(7L, 1L, "PRECISION"))
                .thenReturn(Optional.empty());
        when(observations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var execution = new OvernightExecutionSimulator(new TradingCostProperties(null, null, null, null, null));
        var service = new ClosingCandidateObservationService(runs, observations, stocks, candles,
                calendar, execution, mapper);

        var report = service.track(7L, new BigDecimal("3"), new BigDecimal("-2"));

        assertThat(report.unselected()).isEqualTo(1);
        assertThat(report.completed()).isEqualTo(1);
        assertThat(report.rows().getFirst().status()).isEqualTo("COMPLETED");
        assertThat(report.rows().getFirst().grossReturnRate()).isEqualByComparingTo("0");
        assertThat(report.rows().getFirst().netReturnRate()).isNull();
        assertThat(report.rows().getFirst().costStatus()).isEqualTo("ZERO_COSTS_UNVERIFIED");
    }

    private StockCandle candle(Stock stock, Instant at) {
        return new StockCandle(stock, at, new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("99"), new BigDecimal("100"), 10, new BigDecimal("1000"), true, 1);
    }
}
