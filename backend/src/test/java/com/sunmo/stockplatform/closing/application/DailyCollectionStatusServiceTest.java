/*
 * DailyCollectionStatusService(하루 자동 수집 개수 조회) 테스트예요. H2 메모리 DB에 최소 테이블을 만들고,
 * 공식 run·가상 진입·상한가 감시·후보 맥락·15:25 봉·전일 후보 관측·분봉 보완·DART 행을 넣어 개수가 맞는지 확인해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DailyCollectionStatusServiceTest {
    // 2026-10-02(금)과 전 거래일 2026-10-01(목)이에요. 시각은 UTC로 적고, KST = UTC + 9시간이에요.
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    /** 매 테스트마다 새 H2 DB에 이 서비스가 읽는 열만 가진 테이블을 만들어요. */
    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table closing_recommendation_run(id bigint primary key, recommendation_date date, "
                + "execution_mode varchar(20), strategy_version varchar(40), completed_at timestamp with time zone)");
        jdbc.execute("create table closing_paper_entry(run_id bigint, stock_code varchar(12), status varchar(40))");
        jdbc.execute("create table limit_up_watch(session_date date, sample_label varchar(10), stock_code varchar(12), "
                + "ranking_received_at timestamp with time zone, error text, candidate_source varchar(20), ranking_rows varchar(40))");
        jdbc.execute("create table closing_candidate_context(run_id bigint, stock_code varchar(12), "
                + "received_at timestamp with time zone, error text)");
        jdbc.execute("create table stock_candle(stock_id bigint, timeframe varchar(5), start_time timestamp with time zone, "
                + "volume bigint, source varchar(20), updated_at timestamp with time zone)");
        jdbc.execute("create table closing_candidate_observation(run_id bigint, expected_session_date date, "
                + "status varchar(30), evaluated_at timestamp with time zone, candidate_source varchar(20), missing_intervals text)");
        jdbc.execute("create table dart_filing(rcept_no varchar(20), rcept_dt date, fetched_at timestamp with time zone)");
        jdbc.execute("create table daily_adjusted_sweep_state(stock_id bigint, swept_at timestamp with time zone)");
        jdbc.execute("create table market_index_daily(index_code varchar(10), trade_date date, fetched_at timestamp with time zone)");
        jdbc.execute("create table stock(id bigint, is_active boolean, is_etf boolean, is_etn boolean)");
        jdbc.execute("insert into stock values (1, true, false, false), (2, true, false, false), (3, true, true, false), "
                + "(4, false, false, false)");
        jdbc.execute("create table market_wide_scan_run(session_date date, status varchar(20), completed_at timestamp with time zone)");
    }

    @AfterEach void close() { db.shutdown(); }

    /** 고정 시각(UTC 문자열)을 "지금"으로 쓰는 서비스를 만들어요. 정적 휴장일은 없어요. */
    DailyCollectionStatusService service(String nowUtc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC));
        return new DailyCollectionStatusService(jdbc, calendar);
    }

    static Timestamp at(String utc) { return Timestamp.from(Instant.parse(utc)); }

    @Test
    void countsEveryAutomaticCollectionOfTheDay() {
        // 공식 run 2개 중 최신 FORWARD(11)만 쓰고, REPLAY(12)는 무시해요.
        jdbc.update("insert into closing_recommendation_run values (10, ?, 'FORWARD', 'v9_1', ?), (11, ?, 'FORWARD', 'v9_1', ?), "
                + "(12, ?, 'REPLAY', 'v9_1', ?)", FRIDAY, at("2026-10-02T06:15:05Z"), FRIDAY, at("2026-10-02T06:15:08Z"),
                FRIDAY, at("2026-10-02T09:00:00Z"));
        jdbc.update("insert into closing_paper_entry values (11, 'A', 'ENTERED'), (11, 'B', 'ENTERED'), "
                + "(11, 'C', 'NO_FRESH_TICK_BEFORE_DEADLINE'), (10, 'D', 'ENTERED')");
        jdbc.update("insert into limit_up_watch values (?, '15:16', 'A', ?, null, 'RANKING', 'KOSPI=1,KOSDAQ=2'), "
                + "(?, '15:16', 'B', ?, 'EGW00201', 'BROAD_SNAPSHOT', 'KOSPI=1,KOSDAQ=2'), (?, '15:18', 'A', ?, null, null, null)",
                FRIDAY, at("2026-10-02T06:16:16Z"), FRIDAY, at("2026-10-02T06:16:17Z"), FRIDAY, at("2026-10-02T06:18:31Z"));
        jdbc.update("insert into closing_candidate_context values (11, 'A', ?, null), (11, 'B', ?, 'timeout'), (10, 'C', ?, null)",
                at("2026-10-02T06:16:46Z"), at("2026-10-02T06:19:01Z"), at("2026-10-02T06:16:46Z"));
        // 시장 스캔 기록: 그날 완료 2·실패 1, 전날 완료 1은 세지 않아요.
        jdbc.update("insert into market_wide_scan_run values (?, 'COMPLETED', ?), (?, 'COMPLETED', ?), (?, 'FAILED', null), "
                + "(?, 'COMPLETED', ?)", FRIDAY, at("2026-10-02T00:05:00Z"), FRIDAY, at("2026-10-02T06:18:11Z"), FRIDAY,
                FRIDAY.minusDays(1), at("2026-10-01T06:18:11Z"));
        // 15:25(KST) 봉 3종목 중 2종목만 거래량이 있고, 같은 날 분봉 보완 봉이 2개 있어요(다른 날 보완 봉은 세지 않아요).
        jdbc.update("insert into stock_candle(stock_id, timeframe, start_time, volume, source) values (1, '5M', ?, 100, 'REALTIME'), "
                + "(2, '5M', ?, 0, 'REALTIME'), (3, '5M', ?, 50, 'BACKFILL'), (3, '5M', ?, 20, 'BACKFILL'), (4, '5M', ?, 10, 'BACKFILL')",
                at("2026-10-02T06:25:00Z"), at("2026-10-02T06:25:00Z"), at("2026-10-02T06:25:00Z"),
                at("2026-10-02T01:00:00Z"), at("2026-10-01T01:00:00Z"));
        // 08:10(KST) 일봉 준비가 2종목에 3개, 14:25 정밀 보완이 1종목에 1개 저장했고, 전날 저장분은 세지 않아요.
        jdbc.update("insert into stock_candle(stock_id, timeframe, start_time, volume, source, updated_at) values "
                + "(1, '1D', ?, 1, 'KIS', ?), (1, '1D', ?, 1, 'KIS', ?), (2, '1D', ?, 1, 'KIS', ?), (3, '1D', ?, 1, 'KIS', ?), "
                + "(4, '1D', ?, 1, 'KIS', ?)",
                at("2026-09-30T15:00:00Z"), at("2026-10-01T23:10:05Z"), at("2026-10-01T15:00:00Z"), at("2026-10-01T23:10:06Z"),
                at("2026-10-01T15:00:00Z"), at("2026-10-01T23:10:09Z"), at("2026-10-01T15:00:00Z"), at("2026-10-02T05:25:01Z"),
                at("2026-09-29T15:00:00Z"), at("2026-09-30T23:10:00Z"));
        // 이 날을 관측 세션으로 갖는 전일 후보 관측과, 다른 세션 관측(세지 않음)이에요.
        // 정밀 후보만 statuses에, Broad 후보는 broadStatuses에 따로 세요.
        // 15:25(KST) 종가 봉만 빠진 정밀 관측 1건과, 장중 봉도 빠진 1건이에요. 앞의 것만 "18:00 재보완 대기"로 세요.
        jdbc.update("insert into closing_candidate_observation values (9, ?, 'DATA_INCOMPLETE', ?, 'PRECISION', "
                + "'[\"2026-10-02T06:25:00Z\"]'), (9, ?, 'DATA_INCOMPLETE', ?, 'PRECISION', "
                + "'[\"2026-10-02T01:00:00Z\",\"2026-10-02T06:25:00Z\"]')", FRIDAY, at("2026-10-02T06:40:04Z"), FRIDAY,
                at("2026-10-02T06:40:05Z"));
        jdbc.update("insert into closing_candidate_observation(run_id, expected_session_date, status, evaluated_at, candidate_source) "
                + "values (9, ?, 'COMPLETED', ?, 'PRECISION'), "
                + "(9, ?, 'DATA_MISSING', ?, 'PRECISION'), (9, ?, 'COMPLETED', ?, 'PRECISION'), (9, ?, 'DATA_MISSING', ?, 'BROAD')",
                FRIDAY, at("2026-10-02T06:40:02Z"), FRIDAY, at("2026-10-02T06:40:03Z"),
                LocalDate.of(2026, 10, 1), at("2026-10-01T06:40:00Z"), FRIDAY, at("2026-10-02T09:01:00Z"));
        // 18:00(KST) 재보완이 그날 5분봉 1개를 다시 저장했어요(그날 보완 봉 수에도 들어가요).
        jdbc.update("insert into stock_candle(stock_id, timeframe, start_time, volume, source, updated_at) values "
                + "(5, '5M', ?, 7, 'BACKFILL', ?)", at("2026-10-02T01:05:00Z"), at("2026-10-02T09:00:30Z"));
        // 16:05(KST) 지수 일봉이 그날 일봉 2개를 저장했고, 전날 일봉 갱신은 세지 않아요.
        jdbc.update("insert into market_index_daily values ('0001', ?, ?), ('1001', ?, ?), ('0001', ?, ?)",
                FRIDAY, at("2026-10-02T07:05:00Z"), FRIDAY, at("2026-10-02T07:05:01Z"),
                LocalDate.of(2026, 10, 1), at("2026-10-02T07:05:02Z"));
        jdbc.update("insert into dart_filing values ('1', ?, ?), ('2', ?, ?), ('3', ?, ?)", FRIDAY, at("2026-10-02T09:10:00Z"),
                FRIDAY, at("2026-10-02T22:40:00Z"), LocalDate.of(2026, 10, 1), at("2026-10-01T09:10:00Z"));

        // 18:30(KST) 수정주가 수집이 이날 확인한 종목 2개와, 전날 확인 기록 1개(세지 않음)예요.
        jdbc.update("insert into daily_adjusted_sweep_state values (1, ?), (2, ?), (3, ?)", at("2026-10-02T09:30:05Z"),
                at("2026-10-02T09:31:00Z"), at("2026-10-01T09:30:00Z"));

        var status = service("2026-10-02T10:00:00Z").status(null);

        assertThat(status.date()).isEqualTo(FRIDAY);
        assertThat(status.tradingDay()).isTrue();
        assertThat(status.previousTradingDay()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(status.run().runId()).isEqualTo(11);
        assertThat(status.paperEntries()).isEqualTo(Map.of("ENTERED", 2, "NO_FRESH_TICK_BEFORE_DEADLINE", 1));
        assertThat(status.limitUp()).hasSize(2);
        var first = status.limitUp().get(0);
        assertThat(first.sampleLabel()).isEqualTo("15:16");
        assertThat(first.rows()).isEqualTo(2);
        assertThat(first.errors()).isEqualTo(1);
        assertThat(first.rankingRows()).isEqualTo(2);
        assertThat(first.sources()).isEqualTo(Map.of("RANKING", 1, "BROAD_SNAPSHOT", 1));
        assertThat(first.firstReceivedAt()).isEqualTo(Instant.parse("2026-10-02T06:16:16Z"));
        assertThat(status.limitUp().get(1).sources()).isEqualTo(Map.of("UNKNOWN", 1));
        assertThat(status.candidateContext()).isEqualTo(new DailyCollectionStatusService.Counted(2, 1,
                Instant.parse("2026-10-02T06:19:01Z")));
        assertThat(status.closingBar()).isEqualTo(new DailyCollectionStatusService.ClosingBar(3, 2));
        // 재시작해도 남는 저장 기록: 스캔 완료 2·실패 1, 실시간 5분봉 2종목 2개(분봉 보완 봉은 세지 않아요).
        assertThat(status.scanRuns()).isEqualTo(new DailyCollectionStatusService.Counted(2, 1,
                Instant.parse("2026-10-02T06:18:11Z")));
        assertThat(status.realtimeBars().rows()).isEqualTo(2);
        assertThat(status.realtimeBars().stocks()).isEqualTo(2);
        // 수정주가 수집 대상은 활성·ETF 아님 종목 2개예요.
        assertThat(status.adjustedUniverse()).isEqualTo(2);
        assertThat(status.observation().statuses()).isEqualTo(Map.of("COMPLETED", 1, "DATA_MISSING", 1, "DATA_INCOMPLETE", 2));
        assertThat(status.observation().closingBarOnly()).isEqualTo(1);
        assertThat(status.observation().broadStatuses()).isEqualTo(Map.of("DATA_MISSING", 1));
        assertThat(status.observation().lastEvaluatedAt()).isEqualTo(Instant.parse("2026-10-02T09:01:00Z"));
        assertThat(status.observation().backfilledBars()).isEqualTo(3);
        assertThat(status.lateObservation()).isEqualTo(new DailyCollectionStatusService.LateObservation(1,
                Instant.parse("2026-10-02T09:01:00Z")));
        assertThat(status.indexDaily()).isEqualTo(new DailyCollectionStatusService.Counted(2, 0,
                Instant.parse("2026-10-02T07:05:01Z")));
        assertThat(status.dart().rows()).isEqualTo(2);
        assertThat(status.dart().lastAt()).isEqualTo(Instant.parse("2026-10-02T22:40:00Z"));
        assertThat(status.adjustedDaily()).isEqualTo(new DailyCollectionStatusService.Counted(2, 0,
                Instant.parse("2026-10-02T09:31:00Z")));
        assertThat(status.morningDaily()).isEqualTo(new DailyCollectionStatusService.DailyCandles(3, 2,
                Instant.parse("2026-10-01T23:10:09Z")));
        assertThat(status.precisionDaily()).isEqualTo(new DailyCollectionStatusService.DailyCandles(1, 1,
                Instant.parse("2026-10-02T05:25:01Z")));
    }

    @Test
    void reportsEmptyCountsWhenNothingWasCollectedYet() {
        var status = service("2026-10-02T00:30:00Z").status(FRIDAY);

        assertThat(status.tradingDay()).isTrue();
        assertThat(status.run()).isNull();
        assertThat(status.paperEntries()).isEmpty();
        assertThat(status.limitUp()).isEmpty();
        assertThat(status.candidateContext().rows()).isZero();
        assertThat(status.closingBar()).isEqualTo(new DailyCollectionStatusService.ClosingBar(0, 0));
        assertThat(status.observation().statuses()).isEmpty();
        assertThat(status.dart().rows()).isZero();
        assertThat(status.adjustedDaily().rows()).isZero();
        assertThat(status.morningDaily().rows()).isZero();
    }

    @Test
    void marksWeekendsAsNonTradingDays() {
        var status = service("2026-10-03T03:00:00Z").status(LocalDate.of(2026, 10, 3));

        assertThat(status.tradingDay()).isFalse();
        assertThat(status.previousTradingDay()).isEqualTo(FRIDAY);
        assertThat(status.run()).isNull();
    }
}
