/*
 * OvernightPerformanceService(마감 추천의 다음 거래일 성과 추적)를 확인하는 테스트예요.
 * 다음 거래일 세션 완결성 규칙(09:00~15:15 5분봉 + 15:25 종가 체결 봉, 15:20 봉은 선택)에 따라 PENDING/IN_PROGRESS/COMPLETED/DATA_INCOMPLETE/DATA_MISSING을 정하는지 봐요.
 * 아침 관측을 최종 종가로 착각하거나 완료된 성과를 다시 계산하면 Forward 표본이 오염되므로 중요해요.
 */
package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 기록원처럼 금요일 추천 한 건의 다음 거래일 결과를 세션 규칙대로 정확히 적는지 확인해요.
 */
class OvernightPerformanceServiceTest {
    // 금요일(09-11)에 추천하고 다음 거래일은 보통 월요일(09-14)이에요.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);
    private static final LocalDate MONDAY = FRIDAY.plusDays(3);
    // 저장소와 추천 엔티티는 목(mock, 가짜 객체)이에요.
    private final ClosingRecommendationRepository recommendations = mock(ClosingRecommendationRepository.class);
    private final OvernightPerformanceRepository performances = mock(OvernightPerformanceRepository.class);
    private final StockCandleRepository candles = mock(StockCandleRepository.class);
    private final ClosingRecommendation recommendation = mock(ClosingRecommendation.class);

    /**
     * 고정 시계 Clock(항상 같은 시각을 돌려주는 시계)을 session 날짜 now(KST)에 맞추고, 주어진 휴장일 달력과
     * 기준가 100인 금요일 추천 1건(id 10), 비용 0 시뮬레이터로 서비스를 만들어요.
     */
    private OvernightPerformanceService service(LocalDate session, LocalTime now, List<LocalDate> holidays) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, holidays), Clock.fixed(at(session, now), ZoneOffset.UTC));
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(recommendation.getStock()).thenReturn(stock);
        when(recommendation.getId()).thenReturn(10L);
        when(recommendation.getRecommendationDate()).thenReturn(FRIDAY);
        when(recommendation.getBuyReferencePrice()).thenReturn(new BigDecimal("100"));
        when(recommendations.findByRecommendationDateOrderByRankAsc(FRIDAY)).thenReturn(List.of(recommendation));
        when(recommendations.findLockedById(10L)).thenReturn(Optional.of(recommendation));
        when(performances.save(any())).thenAnswer(i -> i.getArgument(0));
        return new OvernightPerformanceService(recommendations, performances, candles, calendar, new ObjectMapper(),
                new OvernightExecutionSimulator(new com.sunmo.stockplatform.closing.config.TradingCostProperties(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)), mock(ClosingPaperEntryService.class));
    }

    /**
     * 장중 아침 관측이 최종 종가가 되지 않고, 아직 끝나지 않은 봉의 '확정' 표시는 무시하는지 확인해요.
     * 입력: 월요일 09:13에 09:00/09:05/09:10 봉 3개(모두 확정 표시).
     * 기대: IN_PROGRESS, 종가·종가수익률은 비어 있고 최신 수익률만 있으며, 관측 끝은 09:10(09:10 봉은 09:15에 끝나므로 제외)이에요.
     */
    @Test
    void morningObservationNeverBecomesFinalCloseAndFutureFinalFlagsAreIgnored() {
        var service = service(MONDAY, LocalTime.of(9, 13), List.of());
        stubCandles(bars(MONDAY, 3));
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("IN_PROGRESS");
        assertThat(row.closePrice()).isNull();
        assertThat(row.closeReturnRate()).isNull();
        assertThat(row.latestReturnRate()).isNotNull();
        assertThat(row.observedThrough()).isEqualTo(at(MONDAY, LocalTime.of(9, 10)));
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY);
    }

    /**
     * 휴장일이면 기대 세션이 다음 날로 밀리지만, 월요일 데이터가 없다는 이유만으로 화요일로 밀리지는 않는지 확인해요.
     * 입력: 화요일 16:00, 화요일 봉 78개만 있음 / 같은 상황에서 월요일을 휴장일로 지정.
     * 기대: 휴장일이 없으면 기대 세션 월요일·DATA_MISSING, 월요일이 휴장이면 기대 세션 화요일·COMPLETED예요.
     */
    @Test
    void holidayMovesExpectedSessionButMissingMondayDoesNotMoveItToTuesday() {
        // 월요일이 거래일인데 봉이 없는 경우
        var service = service(MONDAY.plusDays(1), LocalTime.of(16, 0), List.of());
        stubCandles(bars(MONDAY.plusDays(1), 78));
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY);
        assertThat(row.status()).isEqualTo("DATA_MISSING");
        assertThat(row.latestPrice()).isNull();
        // 월요일이 휴장일인 경우
        service = service(MONDAY.plusDays(1), LocalTime.of(16, 0), List.of(MONDAY));
        row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY.plusDays(1));
        assertThat(row.status()).isEqualTo("COMPLETED");
    }

    /**
     * 15:25 종가 체결 봉이나 중간 봉이 빠지면 완료되지 않는지 확인해요.
     * 입력: 16:00에 봉 77개(15:25 봉 없음) / 봉 78개 중 09:10 봉 하나를 뺀 목록.
     * 기대: 둘 다 DATA_INCOMPLETE이고, 첫 경우 missing 목록에 15:25(06:25Z)가 남고 종가는 비어 있어요.
     */
    @Test
    void missingClosingPrintOrInteriorCandlePreventsCompletion() {
        var service = service(MONDAY, LocalTime.of(16, 0), List.of());
        stubCandles(bars(MONDAY, 77));
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("DATA_INCOMPLETE");
        assertThat(row.missingIntervals()).contains("06:25:00Z");
        assertThat(row.closePrice()).isNull();
        var missing = new ArrayList<>(bars(MONDAY, 78));
        missing.remove(2);
        stubCandles(missing);
        assertThat(service.track(FRIDAY, null, null, null).performances().getFirst().status()).isEqualTo("DATA_INCOMPLETE");
    }

    /**
     * 체결이 없는 15:20 봉은 없어도 되고 15:25 종가 체결 봉만 있으면 완료되는지 확인해요.
     * 입력: 봉 78개에서 15:20 봉을 빼고, 15:25 봉 종가를 102로 바꾼 목록.
     * 기대: COMPLETED, missing 없음, 최신가 102, 관측 끝 15:30이에요.
     */
    @Test
    void emptyClosingCallBarIsNotRequiredButTheClosingPrintBarIs() {
        var service = service(MONDAY, LocalTime.of(16, 0), List.of());
        var noCallBar = new ArrayList<>(bars(MONDAY, 78));
        noCallBar.remove(76); // 15:20: no executions during the KRX closing single-price call
        // 제거 뒤 76번째는 15:25 종가 체결 봉이에요.
        when(noCallBar.get(76).getClose()).thenReturn(new BigDecimal("102"));
        stubCandles(noCallBar);
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.missingIntervals()).isEqualTo("[]");
        assertThat(row.latestPrice()).isEqualByComparingTo("102");
        assertThat(row.observedThrough()).isEqualTo(at(MONDAY, LocalTime.of(15, 30)));
    }

    /**
     * 거래량 0으로 채워 넣은 15:25 봉은 종가 체결로 인정하지 않는지 확인해요.
     * 입력: 봉 78개 중 마지막(15:25) 봉 거래량만 0.
     * 기대: DATA_INCOMPLETE이고 missing 목록에 15:25 시작시각 하나만 있어요.
     */
    @Test
    void zeroVolumePadded1525BarIsNotAClosingPrint() {
        var service = service(MONDAY, LocalTime.of(16, 0), List.of());
        var rows = bars(MONDAY, 78);
        when(rows.get(77).getVolume()).thenReturn(0L);
        stubCandles(rows);
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("DATA_INCOMPLETE");
        assertThat(row.missingIntervals()).isEqualTo("[\"" + at(MONDAY, LocalTime.of(15, 25)) + "\"]");
    }

    /**
     * 종가 체결 창이 정리되는 15:32 전에는 종가 봉이 없어도 진행 중으로 두는지 확인해요.
     * 입력: 15:15 봉까지 76개, 시각 15:31:30 / 15:32.
     * 기대: 15:31:30에는 IN_PROGRESS(missing 없음), 15:32에는 DATA_INCOMPLETE예요.
     */
    @Test
    void closingPrintMayStillBeFormingUntilTheAuctionWindowSettles() {
        var service = service(MONDAY, LocalTime.of(15, 31, 30), List.of());
        stubCandles(bars(MONDAY, 76));
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("IN_PROGRESS");
        assertThat(row.missingIntervals()).isEqualTo("[]");
        service = service(MONDAY, LocalTime.of(15, 32), List.of());
        row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("DATA_INCOMPLETE");
    }

    /**
     * 이미 완료된 성과와 이전 기록은 다시 계산하지 않는지 확인해요.
     * 입력: 한 번 추적해 COMPLETED로 저장된 행을 다시 돌려줌 / 새로 만든 OvernightPerformance를 기존 기록으로 돌려줌.
     * 기대: 봉 저장소를 전혀 조회하지 않고 다시 저장하지도 않으며, 계산 버전은 그대로예요.
     */
    @Test
    void completedObservationAndLegacyRecordAreNeverRecalculated() {
        // 준비: 첫 추적으로 COMPLETED 행을 만들고 ArgumentCaptor(호출 인자를 붙잡는 도구)로 저장값을 잡아요.
        var service = service(MONDAY, LocalTime.of(16, 0), List.of());
        stubCandles(bars(MONDAY, 78));
        service.track(FRIDAY, null, null, null);
        var capture = org.mockito.ArgumentCaptor.forClass(OvernightPerformance.class);
        verify(performances).save(capture.capture());
        when(performances.findByRecommendationId(10L)).thenReturn(Optional.of(capture.getValue()));
        clearInvocations(candles, performances);
        // 실행/확인: 완료 행은 재계산 없이 그대로 돌려줘요.
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.targetHit()).isTrue();
        assertThat(row.stopHit()).isTrue();
        verifyNoInteractions(candles);
        verify(performances, never()).save(any());
        // 새 생성자는 이전 계산 버전(VERSION, overnight-performance-v1)으로 만들어져 legacy 기록 역할을 해요.
        // 현재 관측 버전(OBSERVATION_VERSION)으로 바꿔 재계산하지 않는지 확인해요.
        when(performances.findByRecommendationId(10L)).thenReturn(Optional.of(new OvernightPerformance(recommendation)));
        assertThat(service.track(FRIDAY, null, null, null).performances().getFirst().calculationVersion())
                .isEqualTo(OvernightPerformance.VERSION);
        verifyNoInteractions(candles);
    }

    /**
     * 다음 거래일 전에는 PENDING이고, 저장된 뒤에는 목표/손절 기준을 바꿀 수 없는지 확인해요.
     * 입력: 토요일 정오, 봉 없음 / 이후 목표값(1)을 넣어 다시 추적.
     * 기대: PENDING·기대 세션 월요일이고, 기준을 바꾸려 하면 ApplicationException이 나요.
     */
    @Test
    void beforeNextSessionIsPendingAndThresholdsCannotBeChanged() {
        var service = service(FRIDAY.plusDays(1), LocalTime.NOON, List.of());
        stubCandles(List.of());
        var row = service.track(FRIDAY, null, null, null).performances().getFirst();
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.expectedSessionDate()).isEqualTo(MONDAY);
        var capture = org.mockito.ArgumentCaptor.forClass(OvernightPerformance.class);
        verify(performances).save(capture.capture());
        when(performances.findByRecommendationId(10L)).thenReturn(Optional.of(capture.getValue()));
        assertThatThrownBy(() -> service.track(FRIDAY, BigDecimal.ONE, null, null))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
    }

    /**
     * 공식 FORWARD run에서 신호가격, 가상 진입가, 비용 미검증 Net을 구분하는지 확인해요.
     * 입력: 15:00:10에 완료된 FORWARD run, 15:05 진입봉 시가 102, 월요일 봉 78개, 비용 0.
     * 기대: 기준가 100, 가상 진입가 102, Gross는 계산되고 Net은 비우며 비용 상태는 ZERO_COSTS_UNVERIFIED예요.
     */
    @Test
    void officialForwardRunSeparatesSignalPriceVirtualEntryAndUnverifiedZeroCostNet() {
        var service = service(MONDAY, LocalTime.of(16, 0), List.of());
        ClosingRecommendationRun run = mock(ClosingRecommendationRun.class);
        when(run.getExecutionMode()).thenReturn("FORWARD");
        when(run.getCompletedAt()).thenReturn(at(FRIDAY, LocalTime.of(15, 0, 10)));
        when(recommendation.getRun()).thenReturn(run);
        StockCandle entry = bars(FRIDAY, 1).getFirst();
        when(entry.getStartTime()).thenReturn(at(FRIDAY, LocalTime.of(15, 5)));
        when(entry.getOpen()).thenReturn(new BigDecimal("102"));
        List<StockCandle> exitBars = bars(MONDAY, 78);
        // ⚠️ 확인 필요: 첫 조회는 다음 거래일 봉, 두 번째 조회는 진입봉을 돌려줘요. 서비스의 조회 순서가 바뀌면 의미가 뒤바뀌는 순서 의존 스텁이에요.
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), anyString(), any(), any())).thenReturn(exitBars, List.of(entry));

        var row = service.track(FRIDAY, null, null, null).performances().getFirst();

        assertThat(row.buyReferencePrice()).isEqualByComparingTo("100");
        assertThat(row.virtualEntryPrice()).isEqualByComparingTo("102");
        assertThat(row.grossReturnRate()).isNotNull();
        assertThat(row.netReturnRate()).isNull();
        assertThat(row.costStatus()).isEqualTo("ZERO_COSTS_UNVERIFIED");
    }

    /** 기간과 상관없이 봉 조회가 항상 rows를 돌려주도록 목을 설정해요. */
    private void stubCandles(List<StockCandle> rows) {
        when(candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                anyLong(), anyString(), any(), any())).thenReturn(rows);
    }

    /** date 09:00부터 5분 간격 확정봉 count개(시가 100, 고가 104, 저가 97, 종가 101, 거래량 10)를 만들어요. 78개면 15:25 봉까지예요. */
    private List<StockCandle> bars(LocalDate date, int count) {
        return IntStream.range(0, count).mapToObj(index -> {
            StockCandle candle = mock(StockCandle.class);
            when(candle.getStartTime()).thenReturn(at(date, LocalTime.of(9, 0)).plusSeconds(index * 300L));
            when(candle.isFinalCandle()).thenReturn(true);
            when(candle.getOpen()).thenReturn(new BigDecimal("100"));
            when(candle.getClose()).thenReturn(new BigDecimal("101"));
            when(candle.getHigh()).thenReturn(new BigDecimal("104"));
            when(candle.getLow()).thenReturn(new BigDecimal("97"));
            when(candle.getVolume()).thenReturn(10L);
            return candle;
        }).toList();
    }

    /** 거래소 시간대(KST)의 날짜·시각을 Instant로 바꿔요. */
    private Instant at(LocalDate date, LocalTime time) { return date.atTime(time).atZone(ClosingTradingCalendar.ZONE).toInstant(); }
}
