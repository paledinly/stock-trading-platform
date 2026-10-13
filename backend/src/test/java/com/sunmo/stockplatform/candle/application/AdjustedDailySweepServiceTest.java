package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.AdjustedDailySweepProperties;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.candle.DailyCandle;
import com.sunmo.stockplatform.kis.candle.KisDailyCandleClient;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdjustedDailySweepServiceTest {
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    static final LocalDate NEXT_SESSION = LocalDate.of(2026, 10, 6);
    // 210 calendar days before FRIDAY.
    static final LocalDate START = FRIDAY.minusDays(210);
    final KisDailyCandleClient client = mock(KisDailyCandleClient.class);
    final StockRepository stocks = mock(StockRepository.class);
    final KisProperties kis = mock(KisProperties.class);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12))");
        jdbc.update("insert into stock values (1, '005930')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V33__create_daily_adjusted_candle.sql"))
                .execute(db);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");
        when(stocks.findByActiveTrueAndEtfFalseAndEtnFalseOrderByStockCodeAsc()).thenReturn(List.of(stock));
        when(kis.enabled()).thenReturn(true);
    }

    @AfterEach void close() { db.shutdown(); }

    AdjustedDailySweepService service(String utc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(utc), ZoneOffset.UTC));
        return new AdjustedDailySweepService(new AdjustedDailySweepProperties(true, 210, 140), kis, calendar, stocks,
                client, jdbc, new DataSourceTransactionManager(db));
    }

    @Test
    void firstSweepPagesBackToTheLookbackAndASecondRunTheSameDayIsANoOp() {
        // 18:30 KST on a trading day: the session is complete.
        when(client.fetchAdjusted(eq("005930"), eq(START), any())).thenAnswer(call -> {
            LocalDate upper = call.getArgument(2);
            return upper.equals(FRIDAY) ? rows(FRIDAY, 100, date -> "100") : rows(upper, 40, date -> "100");
        });

        var first = run(service("2026-10-02T09:30:00Z"));

        assertThat(first.calls()).isEqualTo(2);
        assertThat(first.savedRows()).isEqualTo(140);
        assertThat(first.fullFetches()).isEqualTo(1);
        verify(client).fetchAdjusted("005930", START, FRIDAY.minusDays(100));
        assertThat(jdbc.queryForObject("select count(*) from daily_adjusted_candle", Integer.class)).isEqualTo(140);
        assertThat(jdbc.queryForObject("select covered_from from daily_adjusted_sweep_state", LocalDate.class))
                .isEqualTo(START);

        var again = run(service("2026-10-02T10:00:00Z"));
        assertThat(again.upToDate()).isEqualTo(1);
        assertThat(again.calls()).isZero();
    }

    @Test
    void nextSessionReadsOnlyTheRecentWindowUnlessAdjustedPricesWereRebased() {
        when(client.fetchAdjusted(eq("005930"), eq(START), any())).thenAnswer(call -> {
            LocalDate upper = call.getArgument(2);
            return upper.equals(FRIDAY) ? rows(FRIDAY, 100, date -> "100") : rows(upper, 40, date -> "100");
        });
        run(service("2026-10-02T09:30:00Z"));

        LocalDate recentFrom = NEXT_SESSION.minusDays(140);
        // Same prices, but volume moved (after-hours trades): updated in place, not treated as a re-base.
        when(client.fetchAdjusted("005930", recentFrom, NEXT_SESSION))
                // About 96 trading days fit in 140 calendar days, so one call (< 100 rows) covers the window.
                .thenReturn(rows(NEXT_SESSION, 90, date -> "100").stream()
                        .filter(row -> !row.date().isBefore(recentFrom))
                        .map(row -> new DailyCandle(row.date(), row.open(), row.high(), row.low(), row.close(), 20,
                                row.tradingValue())).toList());
        var unchanged = run(service("2026-10-06T09:30:00Z"));
        assertThat(unchanged.calls()).isEqualTo(1);
        assertThat(unchanged.revisedRows()).isZero();
        assertThat(unchanged.fullFetches()).isZero();
        assertThat(jdbc.queryForObject("select volume from daily_adjusted_candle where trade_date = ?", Long.class,
                FRIDAY)).isEqualTo(20L);

        // A split re-bases the adjusted history: every overlapping price halves.
        LocalDate later = LocalDate.of(2026, 10, 7);
        LocalDate laterFrom = later.minusDays(140);
        when(client.fetchAdjusted("005930", laterFrom, later)).thenReturn(rows(later, 90, date -> "50").stream()
                .filter(row -> !row.date().isBefore(laterFrom)).toList());
        LocalDate laterStart = later.minusDays(210);
        when(client.fetchAdjusted(eq("005930"), eq(laterStart), any())).thenAnswer(call -> {
            LocalDate upper = call.getArgument(2);
            return upper.equals(later) ? rows(later, 100, date -> "50") : rows(upper, 40, date -> "50");
        });
        var rebased = run(service("2026-10-07T09:30:00Z"));
        assertThat(rebased.revisedRows()).isPositive();
        assertThat(rebased.fullFetches()).isEqualTo(1);
        assertThat(rebased.calls()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from daily_adjusted_candle where trade_date >= ? "
                + "and close_price <> 50", Integer.class, later.minusDays(139)))
                .as("the reloaded history is re-based").isZero();
    }

    @Test
    void refusedDuringTheTradingSession() {
        // 10:00 KST on a trading day.
        var during = service("2026-10-02T01:00:00Z");
        assertThat(during.start()).isFalse();
        var stopped = run(during);
        assertThat(stopped.stoppedReason()).isEqualTo("SESSION_GUARD");
        assertThat(stopped.processed()).isZero();
        verifyNoInteractions(client);
    }

    private static List<DailyCandle> rows(LocalDate newest, int count, Function<LocalDate, String> close) {
        List<DailyCandle> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = newest.minusDays(i);
            BigDecimal price = new BigDecimal(close.apply(date));
            rows.add(new DailyCandle(date, price, price, price, price, 10, price.multiply(BigDecimal.TEN)));
        }
        return rows;
    }

    private static AdjustedDailySweepService.SweepStatus run(AdjustedDailySweepService service) {
        return service.runOnce();
    }
}
