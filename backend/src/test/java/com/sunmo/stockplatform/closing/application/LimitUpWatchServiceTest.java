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

class LimitUpWatchServiceTest {
    final MarketRankingProvider rankings = mock(MarketRankingProvider.class);
    final StockRepository stocks = mock(StockRepository.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final KisProperties kis = mock(KisProperties.class);

    LimitUpWatchService service(String utc, LimitUpWatchService.BookSource books) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(utc), ZoneOffset.UTC));
        return new LimitUpWatchService(true, kis, calendar, rankings, stocks, jdbc, books);
    }

    @Test
    void recordsStocksUpTwentyPercentWithTheirBookAndKeepsFailedBooksAsRows() {
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

        var sample = service("2026-10-02T06:16:15Z", books).sample("15:16");

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

    @Test
    void doesNothingOutsideTheWindowBetweenEvaluationAndEntryDeadline() {
        var sample = service("2026-10-02T06:20:00Z", code -> { throw new AssertionError(); }).sample("15:18");
        assertThat(sample.candidates()).isZero();
        verifyNoInteractions(rankings, jdbc);
    }

    private static KisRankingEntry entry(String code, String rate) {
        return new KisRankingEntry(code, code, 1, new BigDecimal("1000"), new BigDecimal(rate), 10L,
                new BigDecimal("10000"), null, null, new BigDecimal("1000"), null);
    }

    private static Book book(String code, Instant at, String askQty) {
        List<Level> asks = new ArrayList<>(), bids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            asks.add(i == 0 ? new Level(new BigDecimal("1300"), new BigDecimal(askQty)) : new Level(BigDecimal.ZERO, BigDecimal.ZERO));
            bids.add(new Level(new BigDecimal(1300 - i * 10), new BigDecimal("1000")));
        }
        return new Book(code, at, at, bids, asks);
    }
}
