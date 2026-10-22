/*
 * DailyCandleBackfillService(추천 판단에 필요한 과거 일봉을 미리 채우는 서비스)를 확인해요.
 * 추천일 기준으로 "이미 확정된 날"까지의 일봉만 받아야 미래 정보가 섞이지 않고, 스케줄러가 꺼져 있으면 KIS를 부르지 않아야 해요.
 */
package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.DailyCandleBackfillService;
import com.sunmo.stockplatform.candle.application.DailyCandlePersistence;
import com.sunmo.stockplatform.candle.config.DailyCandleBackfillProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.candle.DailyCandle;
import com.sunmo.stockplatform.kis.candle.KisDailyCandleClient;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import com.sunmo.stockplatform.marketwide.infrastructure.PrecisionSubscriptionSessionRepository;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 일봉 사전 보충 범위와 대상 선정을 점검하는 테스트예요. */
class DailyCandleBackfillServiceTest {
    /**
     * 장 마감 뒤에는 다음 거래일을 추천일로 잡고, 그날(미래)의 일봉은 요청하지 않는지 확인해요.
     * 준비: 고정 시계 Clock를 9/17 15:50(서울)으로 두고, 검출 종목(중복 005930)과 관심종목 005930을 목(mock, 가짜 객체)으로 준비해요.
     * 기대: 추천일 9/18, 필요한 마지막 날 9/17, 시도 1종목(중복 제거), 저장 61개, 준비 완료 1종목이고 KIS 요청 끝 날짜는 9/17이에요.
     */
    @Test
    void afterClosePreparesNextTradingDayWithoutRequestingItsFutureCandle() {
        // 준비
        var settings = new DailyCandleBackfillProperties(true, 40, 120, Duration.ofHours(1));
        var kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, List.of()),
                Clock.fixed(Instant.parse("2026-09-17T06:50:00Z"), ZoneOffset.UTC));
        var detections = mock(ScannerDetectionRepository.class);
        when(detections.findRecentStockCodesForSession(eq(LocalDate.of(2026, 9, 17)), any(), any()))
                .thenReturn(List.of("005930", "005930"));
        var watchlist = mock(WatchlistItemRepository.class);
        var stocks = mock(StockRepository.class);
        var client = mock(KisDailyCandleClient.class);
        var persistence = mock(DailyCandlePersistence.class);
        var precisionSessions = mock(PrecisionSubscriptionSessionRepository.class);
        Stock stock = mock(Stock.class);
        when(watchlist.findDistinctStockCodesByOwnerId(1L)).thenReturn(List.of("005930"));
        when(stocks.findByStockCodeAndActiveTrue("005930")).thenReturn(Optional.of(stock));
        when(persistence.ready(stock, LocalDate.of(2026, 9, 17))).thenReturn(false, true);
        when(client.fetch(eq("005930"), any(), eq(LocalDate.of(2026, 9, 17))))
                .thenReturn(List.of(mock(DailyCandle.class)));
        when(persistence.saveMissing(eq(stock), any(), eq(LocalDate.of(2026, 9, 17)), anyList()))
                .thenReturn(61);
        var service = new DailyCandleBackfillService(settings, kis, calendar, detections, watchlist,
                stocks, client, persistence, precisionSessions);

        // 실행
        var result = service.prepare();

        // 확인
        assertThat(result.recommendationDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(result.latestRequiredDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(result.attemptedStocks()).isEqualTo(1);
        assertThat(result.savedCandles()).isEqualTo(61);
        assertThat(result.readyStocks()).isEqualTo(1);
        verify(client).fetch(eq("005930"), any(), eq(LocalDate.of(2026, 9, 17)));
        verify(detections, never()).findByDetectedAtGreaterThanEqualOrderByDetectedAtDesc(any(), any());
    }

    /**
     * 14:25·14:35 정밀 후보 일봉 보완이 오늘 세션 기록이 없는, 전날부터 이어진 정밀 종목도 대상에 넣는지 확인해요.
     * 준비: 10-20(화) 14:25, 등록부에는 정밀 000660(전날부터 이어짐)과 005930, 오늘 세션 기록은 005930만 있어요.
     * 기대: 두 종목 모두 전 거래일(10-19)까지 일봉을 요청하고, 관심종목 전용 035720은 요청하지 않아요.
     */
    @Test
    void precisionPreparationIncludesPrecisionCodesCarriedOverFromYesterday() {
        var kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, List.of()),
                Clock.fixed(Instant.parse("2026-10-20T05:25:00Z"), ZoneOffset.UTC));
        var stocks = mock(StockRepository.class);
        var client = mock(KisDailyCandleClient.class);
        var persistence = mock(DailyCandlePersistence.class);
        var precisionSessions = mock(PrecisionSubscriptionSessionRepository.class);
        var registry = new com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry(
                new com.sunmo.stockplatform.market.config.RealtimeMarketProperties(true, java.net.URI.create("ws://localhost"),
                        Duration.ZERO, Duration.ofHours(1), 10, 41, Duration.ofMinutes(1)));
        registry.add("000660", com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry.Source.PRECISION);
        registry.add("005930", com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry.Source.PRECISION);
        registry.add("035720", com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry.Source.WATCHLIST);
        when(precisionSessions.findBySessionDateAndStatusOrderByRequestedAtAsc(LocalDate.of(2026, 10, 20),
                PrecisionSubscriptionSession.Status.ACTIVE))
                .thenReturn(List.of(new PrecisionSubscriptionSession("005930", Instant.parse("2026-10-20T00:00:00Z"), true)));
        for (String code : List.of("000660", "005930")) {
            Stock stock = mock(Stock.class);
            when(stocks.findByStockCodeAndActiveTrue(code)).thenReturn(Optional.of(stock));
            when(persistence.ready(stock, LocalDate.of(2026, 10, 19))).thenReturn(false, true);
        }
        when(client.fetch(any(), any(), any())).thenReturn(List.of(mock(DailyCandle.class)));
        var service = new DailyCandleBackfillService(new DailyCandleBackfillProperties(true, 40, 120, Duration.ofHours(1)),
                kis, calendar, mock(ScannerDetectionRepository.class), mock(WatchlistItemRepository.class), stocks, client,
                persistence, precisionSessions, registry);

        service.preparePrecision();

        verify(client).fetch(eq("000660"), any(), eq(LocalDate.of(2026, 10, 19)));
        verify(client).fetch(eq("005930"), any(), eq(LocalDate.of(2026, 10, 19)));
        verify(client, never()).fetch(eq("035720"), any(), any());
    }

    /**
     * 기능이 꺼져 있으면 예약 실행이 KIS를 전혀 부르지 않는지 확인해요.
     * 준비: enabled=false 설정으로 서비스를 만들고 scheduledPrepare를 호출해요.
     * 기대: 일봉 클라이언트와 상호작용이 없어요.
     */
    @Test
    void disabledSchedulerMakesNoKisCalls() {
        var kis = mock(KisProperties.class);
        var client = mock(KisDailyCandleClient.class);
        var service = new DailyCandleBackfillService(
                new DailyCandleBackfillProperties(false, 40, 120, Duration.ofHours(1)), kis,
                mock(ClosingTradingCalendar.class), mock(ScannerDetectionRepository.class),
                mock(WatchlistItemRepository.class), mock(StockRepository.class), client,
                mock(DailyCandlePersistence.class), mock(PrecisionSubscriptionSessionRepository.class));
        service.scheduledPrepare();
        verifyNoInteractions(client);
    }

    /**
     * 장중 정밀 후보 준비는 관심종목·검출 없이 활성 정밀 구독 종목만 대상으로 하는지 확인해요.
     * 준비: 고정 시계 Clock를 9/17 14:30(서울)으로 두고, 활성 정밀 구독 039030 한 건을 준비해요.
     * 기대: 후보 1종목, 준비 완료 1종목이고 아직 장중이라 KIS 요청 끝 날짜는 전날 9/16이에요.
     */
    @Test
    void precisionPreparationFetchesActiveCandidateWithoutWatchlistOrDetection() {
        // 준비
        var settings = new DailyCandleBackfillProperties(true, 40, 120, Duration.ofHours(1));
        var kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 0, 0, false,
                null, null, null, null, null, List.of()),
                Clock.fixed(Instant.parse("2026-09-17T05:30:00Z"), ZoneOffset.UTC));
        var precisionSessions = mock(PrecisionSubscriptionSessionRepository.class);
        var session = new PrecisionSubscriptionSession("039030", Instant.parse("2026-09-17T05:20:00Z"), true);
        when(precisionSessions.findBySessionDateAndStatusOrderByRequestedAtAsc(
                LocalDate.of(2026, 9, 17), PrecisionSubscriptionSession.Status.ACTIVE))
                .thenReturn(List.of(session));
        var stocks = mock(StockRepository.class);
        Stock stock = mock(Stock.class);
        when(stocks.findByStockCodeAndActiveTrue("039030")).thenReturn(Optional.of(stock));
        var client = mock(KisDailyCandleClient.class);
        when(client.fetch(eq("039030"), any(), eq(LocalDate.of(2026, 9, 16))))
                .thenReturn(List.of(mock(DailyCandle.class)));
        var persistence = mock(DailyCandlePersistence.class);
        when(persistence.ready(stock, LocalDate.of(2026, 9, 16))).thenReturn(false, true);
        when(persistence.saveMissing(eq(stock), any(), eq(LocalDate.of(2026, 9, 16)), anyList()))
                .thenReturn(61);
        var service = new DailyCandleBackfillService(settings, kis, calendar,
                mock(ScannerDetectionRepository.class), mock(WatchlistItemRepository.class), stocks,
                client, persistence, precisionSessions);

        // 실행
        var result = service.preparePrecision();

        // 확인
        assertThat(result.candidateStocks()).isEqualTo(1);
        assertThat(result.readyStocks()).isEqualTo(1);
        verify(client).fetch(eq("039030"), any(), eq(LocalDate.of(2026, 9, 16)));
    }
}
