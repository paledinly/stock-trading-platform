/*
 * AdjustedDailySweepService(장 마감 뒤 수정주가 일봉을 훑어 저장하는 서비스)를 확인해요.
 * 처음엔 정해진 기간(210일)까지 거슬러 받고, 이후엔 최근 구간만 읽되 액면분할 등으로 과거 수정주가가 바뀌면 전체를 다시 받아야 해요.
 * H2(테스트용 메모리 DB)에 실제 마이그레이션 SQL로 테이블을 만들어 저장 결과까지 확인해요.
 */
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

/** 수정주가 일봉 일괄 갱신(sweep) 규칙을 점검하는 테스트예요. */
class AdjustedDailySweepServiceTest {
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    static final LocalDate NEXT_SESSION = LocalDate.of(2026, 10, 6);
    // 210 calendar days before FRIDAY.
    static final LocalDate START = FRIDAY.minusDays(210);
    // KIS 일봉 클라이언트·종목 저장소·KIS 설정은 목(mock, 가짜 객체)이에요.
    final KisDailyCandleClient client = mock(KisDailyCandleClient.class);
    final StockRepository stocks = mock(StockRepository.class);
    final KisProperties kis = mock(KisProperties.class);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    /** 테스트마다 새 H2 DB를 만들고, 종목 테이블과 수정주가 일봉 테이블(V33 마이그레이션)을 준비해요. */
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

    /** 테스트가 끝나면 H2 DB를 닫아요. */
    @AfterEach void close() { db.shutdown(); }

    /** 지정한 UTC 시각의 고정 시계 Clock(항상 같은 시각)로 서비스를 만드는 도우미예요(전체 210일, 최근 140일 설정). */
    AdjustedDailySweepService service(String utc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(utc), ZoneOffset.UTC));
        return new AdjustedDailySweepService(new AdjustedDailySweepProperties(true, 210, 140), kis, calendar, stocks,
                client, jdbc, new DataSourceTransactionManager(db));
    }

    /**
     * 첫 실행은 조회 기간 끝까지 페이지를 넘겨 받고, 같은 날 두 번째 실행은 아무것도 하지 않는지 확인해요.
     * 준비: 금요일 18:30(서울)에 실행하고, KIS 응답은 첫 페이지 100행·다음 페이지 40행이 오게 해요.
     * 기대: 호출 2번, 저장 140행, 전체 조회 1번, covered_from이 시작일이고, 30분 뒤 재실행은 최신 상태(호출 0)예요.
     */
    @Test
    void firstSweepPagesBackToTheLookbackAndASecondRunTheSameDayIsANoOp() {
        // 18:30 KST on a trading day: the session is complete.
        when(client.fetchAdjusted(eq("005930"), eq(START), any())).thenAnswer(call -> {
            LocalDate upper = call.getArgument(2);
            return upper.equals(FRIDAY) ? rows(FRIDAY, 100, date -> "100") : rows(upper, 40, date -> "100");
        });

        // 실행: 첫 번째 sweep
        var first = run(service("2026-10-02T09:30:00Z"));

        // 확인
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

    /**
     * 다음 거래일엔 최근 구간만 읽고, 수정주가 기준이 바뀌었을 때만 전체를 다시 받는지 확인해요.
     * 준비: 첫 sweep 뒤, 10/6에는 가격은 같고 거래량만 바뀐 최근 응답을, 10/7에는 가격이 절반(액면분할)이 된 응답을 줘요.
     * 기대: 10/6은 호출 1번·수정 0·전체 조회 0이고 거래량만 갱신돼요. 10/7은 수정 행이 생기고 전체 조회 1번·호출 3번이며 다시 받은 기간의 종가는 모두 50이에요.
     */
    @Test
    void nextSessionReadsOnlyTheRecentWindowUnlessAdjustedPricesWereRebased() {
        // 준비: 첫 sweep으로 기본 데이터를 채워요.
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

    /**
     * 장 중에는 sweep을 거부하는지 확인해요.
     * 준비: 거래일 10:00(서울) 시각으로 서비스를 만들어요.
     * 기대: start가 false, 중단 사유 SESSION_GUARD, 처리 0이고 KIS는 호출되지 않아요.
     */
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

    /** 가장 최근 날짜부터 하루씩 거슬러 count개의 일봉을 만드는 도우미예요(주말도 포함한 단순 달력일이에요). */
    private static List<DailyCandle> rows(LocalDate newest, int count, Function<LocalDate, String> close) {
        List<DailyCandle> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = newest.minusDays(i);
            BigDecimal price = new BigDecimal(close.apply(date));
            rows.add(new DailyCandle(date, price, price, price, price, 10, price.multiply(BigDecimal.TEN)));
        }
        return rows;
    }

    /** sweep을 한 번 실행하는 도우미예요. */
    private static AdjustedDailySweepService.SweepStatus run(AdjustedDailySweepService service) {
        return service.runOnce();
    }
}
