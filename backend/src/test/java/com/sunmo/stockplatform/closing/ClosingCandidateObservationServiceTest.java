/*
 * ClosingCandidateObservationService(마감 후보 관측 서비스)를 검사하는 테스트예요.
 * 최종 추천(SELECTED)에 뽑히지 않은 후보도 같은 가상 체결 모델로 다음 거래일 성과를 추적하는지,
 * 다음날 관측용 구독 후보를 어떤 순서로 고르는지 확인해요. 추천 밖 후보와 비교해야 전략을 공정하게 검증할 수 있어서 중요해요.
 */
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

/**
 * 추천에서 빠진 후보까지 빠짐없이 다음 거래일 성적표를 매기는지 확인하는 테스트 모음이에요.
 */
class ClosingCandidateObservationServiceTest {
    /**
     * 추천되지 않은(EXCLUDED) FORWARD 후보도 추천 종목과 같은 가상 체결 모델로 관측되는지 확인해요.
     * 준비: 공식 FORWARD run 1건, 제외된 정밀 후보 1개, 진입 5분봉 1개와 다음날 09:00~15:25 5분봉(15:20 봉 제외),
     * 비용이 모두 비어 있는 TradingCostProperties, 다음날 15:32의 고정 시각을 돌려주는 달력 목(mock, 가짜 객체)을 둬요.
     * 기대: 미추천 1건이 COMPLETED로 끝나고, 가격이 변하지 않아 총수익률은 0, 비용 미확인이라 순수익률은 null,
     * 비용 상태는 ZERO_COSTS_UNVERIFIED여야 해요.
     */
    @Test
    void tracksUnselectedForwardCandidateWithSameExecutionModel() throws Exception {
        // 준비: 완료된 FORWARD run에 제외 후보 1개를 담아요.
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

        // 준비: 저장소, 5분봉, 거래일 달력을 목으로 연결해요. 달력의 open/close는 UTC라 09:00/15:30 KST에 해당해요.
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
        // No 15:20 bar: the KRX closing single-price call has no executions; 15:25 holds the closing print.
        List<StockCandle> exits = IntStream.range(0, 78).filter(index -> index != 76)
                .mapToObj(index -> candle(stock, nextOpen.plusSeconds(index * 300L))).toList();
        var candles = mock(StockCandleRepository.class);
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(1L), eq("5M"), any(), any())).thenReturn(List.of(entry), exits);
        var calendar = mock(ClosingTradingCalendar.class);
        when(calendar.nextTradingDay(recommendationDate)).thenReturn(nextDate);
        when(calendar.close(recommendationDate)).thenReturn(Instant.parse("2026-09-29T06:20:00Z"));
        when(calendar.open(nextDate)).thenReturn(nextOpen);
        when(calendar.close(nextDate)).thenReturn(Instant.parse("2026-09-30T06:30:00Z"));
        when(calendar.now()).thenReturn(Instant.parse("2026-09-30T06:32:00Z"));
        var observations = mock(ClosingCandidateObservationRepository.class);
        when(observations.findByRunIdAndStockIdAndCandidateSource(7L, 1L, "PRECISION"))
                .thenReturn(Optional.empty());
        when(observations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var execution = new OvernightExecutionSimulator(new TradingCostProperties(null, null, null, null, null));
        var service = new ClosingCandidateObservationService(runs, observations, stocks, candles,
                calendar, execution, mapper, mock(ClosingPaperEntryService.class));

        // 실행: 목표 +3%, 손절 -2% 조건으로 관측을 돌려요.
        var report = service.track(7L, new BigDecimal("3"), new BigDecimal("-2"));

        // 확인: 미추천 후보도 완료되고, 비용 미확인이면 순수익률을 만들지 않아요.
        assertThat(report.unselected()).isEqualTo(1);
        assertThat(report.completed()).isEqualTo(1);
        assertThat(report.rows().getFirst().status()).isEqualTo("COMPLETED");
        assertThat(report.rows().getFirst().grossReturnRate()).isEqualByComparingTo("0");
        assertThat(report.rows().getFirst().netReturnRate()).isNull();
        assertThat(report.rows().getFirst().costStatus()).isEqualTo("ZERO_COSTS_UNVERIFIED");
    }

    /**
     * 다음날 관측용 구독 후보를 SELECTED 먼저, 그다음 WATCH를 점수 높은 순으로 고르고 EXCLUDED는 빼는지 확인해요.
     * 준비: WATCH(60), EXCLUDED(99), SELECTED(40), WATCH(70), 점수 없는 WATCH 5개 후보가 담긴 완료 run을 둬요.
     * 기대: 상한 3이면 000003, 000004, 000001 순서이고, 상한 10이면 점수 없는 000005가 맨 뒤에 붙고, 상한 0이면 비어 있어야 해요.
     */
    @Test
    void subscriptionCandidatesPutSelectedFirstThenWatchByScoreAndSkipExcluded() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LocalDate date = LocalDate.of(2026, 9, 29);
        Instant completedAt = Instant.parse("2026-09-29T06:16:00Z");
        GenerateResponse response = new GenerateResponse(date, completedAt.minusSeconds(10), 4, 0, 1, 2, 1,
                Map.of(), "test-v1", List.of(), completedAt.minusSeconds(60), Map.of(), Map.of(),
                List.of(evaluation("000001", "WATCH", "60"), evaluation("000002", "EXCLUDED", "99"),
                        evaluation("000003", "SELECTED", "40"), evaluation("000004", "WATCH", "70"),
                        evaluation("000005", "WATCH", null)),
                null, "FORWARD", completedAt);
        ClosingRecommendationRun run = new ClosingRecommendationRun(date, completedAt.minusSeconds(10), "test-v1",
                "FORWARD", completedAt.minusSeconds(60), "{}", "hash", "key");
        run.complete(mapper.writeValueAsString(response), completedAt);
        var service = new ClosingCandidateObservationService(mock(ClosingRecommendationRunRepository.class),
                mock(ClosingCandidateObservationRepository.class), mock(StockRepository.class),
                mock(StockCandleRepository.class), mock(ClosingTradingCalendar.class),
                new OvernightExecutionSimulator(new TradingCostProperties(null, null, null, null, null)),
                mapper, mock(ClosingPaperEntryService.class));

        assertThat(service.subscriptionCandidates(run, 3)).containsExactly("000003", "000004", "000001");
        assertThat(service.subscriptionCandidates(run, 10)).containsExactly("000003", "000004", "000001", "000005");
        assertThat(service.subscriptionCandidates(run, 0)).isEmpty();
    }

    /** 종목코드, 처리 결과(disposition), 점수만 바꾼 BROAD 후보 평가 응답을 만들어요. */
    private CandidateEvaluationResponse evaluation(String code, String disposition, String score) {
        return new CandidateEvaluationResponse(code, code, "BROAD", null, Instant.parse("2026-09-29T06:14:00Z"),
                new BigDecimal("100"), score == null ? null : new BigDecimal(score), null, null, "BROAD_B",
                4, 20, List.of(), disposition, "REASON", "{}", "{}", null, Map.of());
    }

    /** 가격이 100 근처에 고정된 확정 5분봉을 지정 시작시각으로 만들어요. */
    private StockCandle candle(Stock stock, Instant at) {
        return new StockCandle(stock, at, new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("99"), new BigDecimal("100"), 10, new BigDecimal("1000"), true, 1);
    }
}
