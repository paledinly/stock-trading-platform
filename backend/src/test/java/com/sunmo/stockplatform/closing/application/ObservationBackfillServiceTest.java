/*
 * ObservationBackfillService 가 전 거래일 공식(FORWARD) 실행의 정밀 후보에 대해 오늘 빠진 5분봉을 장 마감 후 보충하는지 확인해요.
 * 다음 거래일 세션이 완결돼야 성과가 COMPLETED 가 되므로, 빠진 봉과 거래량 0 인 자리표시 봉을 찾아 채우되 종가 확정 전에는 손대지 않는 것이 중요해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 관측 보충 작업이 필요한 봉만 정확한 시각에 요청하는지 확인하는 테스트예요. */
class ObservationBackfillServiceTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    static final LocalDate PREVIOUS = LocalDate.of(2026, 10, 1);
    final ClosingRecommendationRunRepository runs = mock(ClosingRecommendationRunRepository.class);
    final StockRepository stocks = mock(StockRepository.class);
    final StockCandleRepository candles = mock(StockCandleRepository.class);
    final CandleBackfillService backfill = mock(CandleBackfillService.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    /** 주어진 UTC 시각으로 고정한 시계 Clock 의 달력과 목(mock, 가짜 객체) 의존성으로 서비스를 만들어요. */
    ObservationBackfillService service(String utc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(utc), ZoneOffset.UTC));
        return new ObservationBackfillService(calendar, runs, stocks, candles, backfill, jdbc);
    }

    /**
     * 무엇: 전일(10/01) FORWARD 실행 후보 종목의 오늘 5분봉 중 빠진 봉과 거래량 0 자리표시 봉을 보충 요청하는지 확인해요.
     * 입력: 09:00~15:10 봉은 있고 15:15 봉은 없으며 15:25 봉은 거래량 0, 현재 시각 15:33(한국시간).
     * 기대: 보충 대상이 15:15, 15:25 두 시각이고(15:20 봉은 종가 단일가 접수 시간이라 원래 비어 있으므로 요청하지 않아요), run id 63, 여전히 빠진 종목 1개(목이라 보충 결과가 반영되지 않음)예요.
     */
    @Test
    void fillsMissingBarsOfPreviousRunPrecisionCandidatesExceptTheClosingCall() {
        ClosingRecommendationRun run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(63L);
        when(runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(PREVIOUS, "FORWARD"))
                .thenReturn(Optional.of(run));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(63L), eq(63L))).thenReturn(List.of(7L));
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(7L);
        when(stocks.findById(7L)).thenReturn(Optional.of(stock));
        // Realtime bars 09:00..15:10 exist; 15:15 is missing; 15:25 is a flat zero-volume placeholder.
        List<StockCandle> stored = new ArrayList<>();
        Instant open = Instant.parse("2026-10-02T00:00:00Z");
        for (Instant t = open; t.isBefore(Instant.parse("2026-10-02T06:15:00Z")); t = t.plusSeconds(300))
            stored.add(bar(t, 10));
        stored.add(bar(Instant.parse("2026-10-02T06:25:00Z"), 0));
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                eq(7L), eq("5M"), any(), any())).thenReturn(stored);

        // 실행: 종가 체결 확정(15:32) 이후인 15:33 에 보충을 돌려요.
        var result = service("2026-10-02T06:33:00Z").fillToday();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<Instant>> gaps = ArgumentCaptor.forClass(List.class);
        verify(backfill).backfillToday(eq(stock), gaps.capture(), any());
        assertThat(gaps.getValue()).containsExactly(Instant.parse("2026-10-02T06:15:00Z"),
                Instant.parse("2026-10-02T06:25:00Z"));
        assertThat(result.runId()).isEqualTo(63L);
        assertThat(result.stillMissingStocks()).isEqualTo(1);
    }

    /**
     * 무엇: 종가 체결이 확정되기 전에는 보충을 시작하지 않는지 확인해요.
     * 입력: 현재 시각 15:31(한국시간).
     * 기대: 결과 null, 보충 서비스 호출 없음.
     */
    @Test
    void doesNothingBeforeTheClosingPrintIsFinal() {
        assertThat(service("2026-10-02T06:31:00Z").fillToday()).isNull();
        verifyNoInteractions(backfill);
    }

    /** 시작시각과 거래량만 가진 확정 5분봉 목을 만들어요. */
    private static StockCandle bar(Instant start, long volume) {
        StockCandle candle = mock(StockCandle.class);
        when(candle.getStartTime()).thenReturn(start);
        when(candle.isFinalCandle()).thenReturn(true);
        when(candle.getVolume()).thenReturn(volume);
        return candle;
    }
}
