/*
 * LimitUpWatchService(상한가 근접 종목 관찰 기록)를 검사하는 테스트예요.
 * 15:15 평가 이후 15:20 진입 마감 전 사이에 20% 이상 오른 종목과 그 호가를 기록하고, 호가 조회 실패도 행으로 남기는지 확인해요.
 * 상한가 잠김 종목은 실제로 살 수 없을 수 있어서, 나중에 가상 진입 가능성을 검토할 증거가 되기 때문에 중요해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.Book;
import com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.Level;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;
import com.sunmo.stockplatform.kis.ranking.MarketRankingProvider;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 상한가 근처 종목을 정해진 시간 창에서만, 빠짐없이 기록하는지 확인하는 테스트 모음이에요.
 */
class LimitUpWatchServiceTest {
    // 순위 조회, 종목 저장소, JDBC, KIS 설정은 모두 목(mock, 가짜 객체)이에요.
    final MarketRankingProvider rankings = mock(MarketRankingProvider.class);
    final StockRepository stocks = mock(StockRepository.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final KisProperties kis = mock(KisProperties.class);

    /** 지정한 UTC 시각에 멈춘 고정 시계 Clock과 실제 거래일 달력으로 서비스를 만들어요. */
    LimitUpWatchService service(String utc, LimitUpWatchService.BookSource books) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(utc), ZoneOffset.UTC));
        return new LimitUpWatchService(true, kis, calendar, rankings, stocks, jdbc, books);
    }

    /**
     * 20% 이상 오른 종목만 등락률 높은 순으로 호가와 함께 기록하고, 호가 조회 실패 종목도 행으로 남기는지 확인해요.
     * 준비: KOSPI 29.9%, 22.0%, 15.0%와 KOSDAQ 25.0% 종목, 22.0% 종목은 호가 조회가 EGW00201(호출 제한)로 실패해요.
     * 시각은 15:16:15 KST예요. 기대: 후보 3, 호가 2, 실패 1이고, 저장 순서는 000010, 000040, 000020이에요.
     * 실패 행은 호가 시각이 null이고 오류 문구가 남으며, 상한가 잠김 종목의 매도 잔량은 0이에요.
     */
    @Test
    void recordsStocksUpTwentyPercentWithTheirBookAndKeepsFailedBooksAsRows() {
        // 준비: 순위 응답과 호가 공급원을 정해요.
        when(stocks.findByStockCode(anyString())).thenReturn(Optional.empty());
        when(rankings.fetch(RankingType.PRICE_RISE, Market.KOSPI, 100)).thenReturn(List.of(
                entry("000010", "29.9"), entry("000020", "22.0"), entry("000030", "15.0")));
        when(rankings.fetch(RankingType.PRICE_RISE, Market.KOSDAQ, 100)).thenReturn(List.of(entry("000040", "25.0")));
        Instant at = Instant.parse("2026-10-02T06:16:16Z");
        LimitUpWatchService.BookSource books = code -> {
            if (code.equals("000020")) throw new IllegalStateException("EGW00201");
            // Locked at the limit: no ask quantity, huge bid.
            return book(code, at, code.equals("000010") ? "0" : "300");
        };

        // 실행
        var sample = service("2026-10-02T06:16:15Z", books).sample("15:16");

        // 확인: 저장된 SQL 인자 배열의 순서와 내용을 봐요(2=종목코드, 8=호가 시각, 11=매도 잔량으로 보여요, 16=오류).
        assertThat(sample.candidates()).isEqualTo(3);
        assertThat(sample.books()).isEqualTo(2);
        assertThat(sample.failures()).isEqualTo(1);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(anyString(), args.capture());
        List<String> codes = new ArrayList<>();
        for (Object[] row : args.getAllValues()) codes.add((String) row[2]);
        assertThat(codes).as("highest change first").containsExactly("000010", "000040", "000020");
        Object[] failed = args.getAllValues().get(2);
        assertThat(failed[8]).as("no book time on failure").isNull();
        assertThat((String) failed[16]).contains("EGW00201");
        assertThat((BigDecimal) args.getAllValues().get(0)[11]).isEqualByComparingTo("0");
    }

    /**
     * 순위에는 없고 시장 전체 스캔 스냅샷에만 보인 상한가 근접 종목도 추가하고, 후보 출처를 기록하는지 확인해요.
     * 준비: KOSPI 순위 30.0% 종목 1개, KOSDAQ 순위는 호출 제한으로 실패, 스냅샷 조회는 29.93% 종목 1행을 돌려줘요.
     * 기대: 후보 2개, 출처가 RANKING과 BROAD_SNAPSHOT으로 남고 순위 상태는 "KOSPI=1,KOSDAQ=ERR"이에요.
     */
    @Test
    void addsNearLimitStocksSeenOnlyInMarketWideSnapshotsAndRecordsTheSource() throws Exception {
        when(stocks.findByStockCode(anyString())).thenReturn(Optional.empty());
        when(rankings.fetch(RankingType.PRICE_RISE, Market.KOSPI, 100)).thenReturn(List.of(entry("011330", "30.0")));
        when(rankings.fetch(RankingType.PRICE_RISE, Market.KOSDAQ, 100)).thenThrow(new IllegalStateException("EGW00201"));
        java.sql.ResultSet row = mock(java.sql.ResultSet.class);
        when(row.getString(1)).thenReturn("010580");
        when(row.getString(2)).thenReturn("snapshot only");
        when(row.getBigDecimal(3)).thenReturn(new BigDecimal("5000"));
        when(row.getBigDecimal(4)).thenReturn(new BigDecimal("29.93"));
        when(row.getBigDecimal(5)).thenReturn(new BigDecimal("5000"));
        when(row.getTimestamp(6)).thenReturn(java.sql.Timestamp.from(Instant.parse("2026-10-02T06:15:30Z")));
        doAnswer(call -> {
            ((org.springframework.jdbc.core.RowCallbackHandler) call.getArgument(1)).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any(Object[].class));
        Instant at = Instant.parse("2026-10-02T06:16:16Z");

        var sample = service("2026-10-02T06:16:15Z", code -> book(code, at, "0")).sample("15:16");

        assertThat(sample.candidates()).isEqualTo(2);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        assertThat(args.getAllValues()).extracting(r -> r[2] + ":" + r[17] + ":" + r[18])
                .containsExactly("011330:RANKING:KOSPI=1,KOSDAQ=ERR", "010580:BROAD_SNAPSHOT:KOSPI=1,KOSDAQ=ERR");
    }

    /**
     * 평가 완료와 진입 마감 사이 시간 창 밖에서는 아무것도 하지 않는지 확인해요.
     * 준비: 시계를 15:20:00 KST에 고정하고, 호가 공급원이 불리면 바로 실패하게 해요.
     * 기대: 후보 0개이고 순위 조회와 DB 저장이 한 번도 일어나지 않아요.
     */
    @Test
    void doesNothingOutsideTheWindowBetweenEvaluationAndEntryDeadline() {
        var sample = service("2026-10-02T06:20:00Z", code -> { throw new AssertionError(); }).sample("15:18");
        assertThat(sample.candidates()).isZero();
        verifyNoInteractions(rankings, jdbc);
    }

    /** 종목코드와 등락률만 바꾼 KIS 순위 항목을 만들어요. */
    private static KisRankingEntry entry(String code, String rate) {
        return new KisRankingEntry(code, code, 1, new BigDecimal("1000"), new BigDecimal(rate), 10L,
                new BigDecimal("10000"), null, null, new BigDecimal("1000"), null);
    }

    /** 매도 1호가(1300)에만 지정 잔량이 있고 매수 10단계에 잔량 1000씩 쌓인 10단계 호가를 만들어요. */
    private static Book book(String code, Instant at, String askQty) {
        List<Level> asks = new ArrayList<>(), bids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            asks.add(i == 0 ? new Level(new BigDecimal("1300"), new BigDecimal(askQty)) : new Level(BigDecimal.ZERO, BigDecimal.ZERO));
            bids.add(new Level(new BigDecimal(1300 - i * 10), new BigDecimal("1000")));
        }
        return new Book(code, at, at, bids, asks);
    }
}
