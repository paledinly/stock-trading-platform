package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingDailyHealthService.*;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClosingDailyHealthServiceTest {
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    static final Instant COMPLETED = Instant.parse("2026-10-02T06:15:08Z");
    static final Instant DEADLINE = Instant.parse("2026-10-02T06:20:00Z");
    static final Instant CHECK = Instant.parse("2026-10-02T06:45:00Z");
    static final RealtimeWindow HEALTHY = new RealtimeWindow(true, 0L,
            Instant.parse("2026-10-02T06:14:30Z"), Instant.parse("2026-10-02T06:20:10Z"));
    final ClosingRecommendationProperties policy = new ClosingRecommendationProperties(20, 4, null, null, null, null, null);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table closing_recommendation_run(id bigint primary key, recommendation_date date, "
                + "completed_at timestamp with time zone, execution_mode varchar(20), strategy_version varchar(40), "
                + "request_key varchar(100))");
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12))");
        jdbc.execute("create table scanner_detection(id bigint primary key, market_session_date date, "
                + "detected_at timestamp with time zone)");
        jdbc.execute("create table closing_recommendation(id bigint primary key, run_id bigint, stock_id bigint)");
        jdbc.execute("create table closing_candidate_observation(id bigint primary key, run_id bigint, "
                + "expected_session_date date, status varchar(20))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__closing_paper_entry.sql"),
                new ClassPathResource("db/migration/V32__create_closing_daily_health.sql")).execute(db);
    }
    @AfterEach void close() { db.shutdown(); }

    ClosingDailyHealthService service(Instant now) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(now, ZoneOffset.UTC));
        return new ClosingDailyHealthService(jdbc, calendar, policy, new ObjectMapper());
    }

    Facts facts(Instant completed, int selected, int entered, Map<String, Integer> observations, RealtimeWindow realtime) {
        return new Facts(7L, completed, DEADLINE, selected, entered, Map.of(), observations, realtime, 3, null);
    }

    @Test void completeOnlyWhenRunEntriesObservationsAndRealtimeAreAllWhole() {
        assertThat(ClosingDailyHealthService.judge(facts(COMPLETED, 2, 2, Map.of("COMPLETED", 3), HEALTHY)))
                .isEqualTo(new Judgement(Verdict.COMPLETE, List.of()));
        assertThat(ClosingDailyHealthService.judge(facts(COMPLETED, 0, 0, Map.of(), HEALTHY)).verdict())
                .as("a NO TRADE day with a completed run is complete").isEqualTo(Verdict.COMPLETE);
    }

    @Test void missingWithoutACompletedOfficialRun() {
        assertThat(ClosingDailyHealthService.judge(new Facts(null, null, DEADLINE, 0, 0, Map.of(), Map.of(), HEALTHY, 0, null)))
                .isEqualTo(new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_MISSING")));
        assertThat(ClosingDailyHealthService.judge(facts(null, 0, 0, Map.of(), HEALTHY)))
                .isEqualTo(new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_INCOMPLETE")));
    }

    @Test void everyGapIsADegradedReason() {
        assertThat(ClosingDailyHealthService.judge(facts(DEADLINE, 2, 1, Map.of("COMPLETED", 1, "PENDING", 1),
                RealtimeWindow.UNOBSERVED)))
                .isEqualTo(new Judgement(Verdict.DEGRADED, List.of("EVALUATION_AFTER_ENTRY_DEADLINE",
                        "SELECTED_ENTRY_MISSING", "REALTIME_UNOBSERVED", "OBSERVATION_INCOMPLETE")));
        assertThat(ClosingDailyHealthService.judge(facts(COMPLETED, 1, 1, Map.of("DATA_MISSING", 1),
                new RealtimeWindow(false, 3L, HEALTHY.start(), HEALTHY.end()))).reasons())
                .containsExactly("REALTIME_DISCONNECTED", "REALTIME_TICKS_DROPPED", "OBSERVATION_INCOMPLETE");
    }

    @Test void noScannerDetectionInTheEvaluationWindowIsDegraded() {
        assertThat(ClosingDailyHealthService.judge(new Facts(7L, COMPLETED, DEADLINE, 0, 0, Map.of(), Map.of(),
                HEALTHY, 0, null))).isEqualTo(new Judgement(Verdict.DEGRADED, List.of("SCANNER_DETECTIONS_MISSING")));
        assertThat(ClosingDailyHealthService.judge(new Facts(7L, COMPLETED, DEADLINE, 0, 0, Map.of(), Map.of(),
                HEALTHY, null, null)).verdict()).as("not counted is not a gap").isEqualTo(Verdict.COMPLETE);
    }

    @Test void recordsTodayFromStoredRowsAndKeepsEarlierRealtimeAfterRestart() {
        // 14:29:59 is before the evaluation start, 14:40 and 15:15:00 are inside it, 15:15:01 is after the freeze.
        jdbc.update("insert into scanner_detection values (1, ?, ?), (2, ?, ?), (3, ?, ?), (4, ?, ?)",
                FRIDAY, Timestamp.from(Instant.parse("2026-10-02T05:29:59Z")),
                FRIDAY, Timestamp.from(Instant.parse("2026-10-02T05:40:00Z")),
                FRIDAY, Timestamp.from(Instant.parse("2026-10-02T06:15:00Z")),
                FRIDAY, Timestamp.from(Instant.parse("2026-10-02T06:15:01Z")));
        jdbc.update("insert into closing_recommendation_run values (6, ?, ?, 'FORWARD', 'closing-recommend-v9_1-1515', "
                + "'official-2026-10-01-closing-recommend-v9_1-1515')", FRIDAY.minusDays(1), Timestamp.from(COMPLETED.minusSeconds(86400)));
        jdbc.update("insert into closing_recommendation_run values (7, ?, ?, 'FORWARD', 'closing-recommend-v9_1-1515', "
                + "'official-2026-10-02-closing-recommend-v9_1-1515')", FRIDAY, Timestamp.from(COMPLETED));
        jdbc.update("insert into closing_recommendation_run values (8, ?, ?, 'REPLAY', 'closing-recommend-v9_1-1515', null)",
                FRIDAY, Timestamp.from(CHECK));
        jdbc.update("insert into stock values (1, '005930'), (2, '000660'), (3, '035720')");
        jdbc.update("insert into closing_recommendation values (1, 7, 1), (2, 7, 2)");
        jdbc.update("insert into closing_paper_entry(run_id, stock_code, completed_at, deadline, status) values "
                + "(7, '005930', ?, ?, 'ENTERED'), (7, '000660', ?, ?, 'NO_FRESH_TICK_BEFORE_DEADLINE'), "
                + "(7, '035720', ?, ?, 'ENTERED')", Timestamp.from(COMPLETED), Timestamp.from(DEADLINE),
                Timestamp.from(COMPLETED), Timestamp.from(DEADLINE), Timestamp.from(COMPLETED), Timestamp.from(DEADLINE));
        jdbc.update("insert into closing_candidate_observation values (1, 6, ?, 'COMPLETED'), (2, 6, ?, 'COMPLETED'), "
                + "(3, 8, ?, 'PENDING')", FRIDAY, FRIDAY, FRIDAY);
        jdbc.update("insert into closing_daily_health(trade_date, verdict, reasons, strategy_version, checked_at) "
                + "values (?, 'MISSING', 'OFFICIAL_RUN_MISSING', 'closing-recommend-v9_1-1515', ?)",
                FRIDAY.minusDays(1), Timestamp.from(CHECK.minusSeconds(86400)));

        DailyHealth first = service(CHECK).recordToday(HEALTHY);

        assertThat(first.verdict()).isEqualTo(Verdict.DEGRADED);
        assertThat(first.reasons()).containsExactly("SELECTED_ENTRY_MISSING");
        assertThat(first.runId()).isEqualTo(7L);
        assertThat(first.runCompletedAt()).isEqualTo(COMPLETED);
        assertThat(first.selectedCount()).isEqualTo(2);
        assertThat(first.selectedEntered()).isEqualTo(1);
        assertThat(first.paperEntryStatuses()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ENTERED", 2, "NO_FRESH_TICK_BEFORE_DEADLINE", 1));
        assertThat(first.observationStatuses()).containsExactlyEntriesOf(Map.of("COMPLETED", 2));

        DailyHealth restarted = service(CHECK.plusSeconds(3600)).recordToday(RealtimeWindow.UNOBSERVED);
        assertThat(restarted.realtimeConnected()).isTrue();
        assertThat(restarted.realtimeWindowStart()).isEqualTo(HEALTHY.start());
        assertThat(restarted.checkedAt()).isEqualTo(CHECK.plusSeconds(3600));

        List<DailyHealth> rows = service(CHECK.plusSeconds(3600)).list(FRIDAY.minusDays(1), FRIDAY);
        assertThat(rows).extracting(DailyHealth::tradeDate).containsExactly(FRIDAY, FRIDAY.minusDays(1));
        assertThat(rows.get(0)).isEqualTo(restarted);
        assertThat(rows.get(1).reasons()).containsExactly("OFFICIAL_RUN_MISSING");
        assertThat(rows.get(1).checkedAt()).as("earlier days are never rewritten").isEqualTo(CHECK.minusSeconds(86400));
        assertThat(jdbc.queryForObject("select count(*) from closing_daily_health", Integer.class)).isEqualTo(2);
    }

    @Test void findsTheRunThatExecutedTodayAfterAVersionChange() {
        jdbc.update("insert into closing_recommendation_run values (7, ?, ?, 'FORWARD', 'closing-recommend-v9-1515', "
                + "'official-2026-10-02-closing-recommend-v9-1515')", FRIDAY, Timestamp.from(COMPLETED));
        jdbc.update("insert into closing_recommendation_run values (8, ?, ?, 'FORWARD', 'closing-recommend-v9-1515', "
                + "'manual-retry')", FRIDAY, Timestamp.from(COMPLETED.plusSeconds(30)));
        jdbc.update("insert into closing_recommendation_run values (9, ?, ?, 'REPLAY', 'closing-recommend-v9_1-1515', null)",
                FRIDAY, Timestamp.from(CHECK));

        DailyHealth row = service(CHECK).recordToday(HEALTHY);

        assertThat(row.runId()).as("the official key of the earlier version wins over a later non-official run")
                .isEqualTo(7L);
        assertThat(row.strategyVersion()).isEqualTo("closing-recommend-v9-1515");
        assertThat(row.reasons()).doesNotContain("OFFICIAL_RUN_MISSING");
    }

    @Test void recordsMissingWhenNoOfficialRunExists() {
        DailyHealth row = service(CHECK).recordToday(HEALTHY);
        assertThat(row.verdict()).isEqualTo(Verdict.MISSING);
        assertThat(row.reasons()).as("a missing run is not also reported as missing detections")
                .doesNotContain("SCANNER_DETECTIONS_MISSING");
        assertThat(row.reasons()).containsExactly("OFFICIAL_RUN_MISSING");
        assertThat(row.selectedCount()).isZero();
    }

    @Test void tradingDaysWithoutARowAreMissingAndClosuresWeekendsAndUncheckedTodayAreOmitted() {
        jdbc.update("insert into closing_daily_health(trade_date, verdict, reasons, strategy_version, run_id, checked_at) "
                + "values (?, 'COMPLETE', '', 'closing-recommend-v9_1-1515', 5, ?)", LocalDate.of(2026, 9, 30),
                Timestamp.from(CHECK.minusSeconds(2 * 86400)));

        List<DailyHealth> rows = service(CHECK).list(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 10, 5));

        // 09-24/25 are verified closures, 09-26/27 a weekend, 10-03..05 are after today.
        assertThat(rows).extracting(DailyHealth::tradeDate).containsExactly(FRIDAY, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 23));
        assertThat(rows).filteredOn(row -> !row.recorded()).allSatisfy(row -> {
            assertThat(row.verdict()).isEqualTo(Verdict.MISSING);
            assertThat(row.reasons()).containsExactly("NO_HEALTH_RECORD");
        });
        assertThat(rows.get(2).recorded()).isTrue();
        assertThat(rows.get(2).verdict()).isEqualTo(Verdict.COMPLETE);
        assertThat(rows.get(2).reasons()).isEmpty();

        assertThat(service(CHECK.minusSeconds(600)).list(FRIDAY.minusDays(1), FRIDAY))
                .as("today is not judged before close + 15m").extracting(DailyHealth::tradeDate)
                .containsExactly(FRIDAY.minusDays(1));
        assertThatThrownBy(() -> service(CHECK).list(FRIDAY, FRIDAY.minusDays(1))).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> service(CHECK).list(FRIDAY.minusDays(400), FRIDAY)).isInstanceOf(ApplicationException.class);
    }

    @Test void recorderSamplesAroundFreezeAndRecordsOncePerTradingDay() {
        var health = mock(ClosingDailyHealthService.class);
        var calendar = mock(ClosingTradingCalendar.class);
        var diagnostics = new RealtimeDiagnostics();
        when(calendar.isTradingDay(FRIDAY)).thenReturn(true);
        when(calendar.close(FRIDAY)).thenReturn(Instant.parse("2026-10-02T06:30:00Z"));
        when(health.recordToday(any())).thenReturn(DailyHealth.missing(FRIDAY));
        var recorder = new ClosingDailyHealthRecorder(health, diagnostics, calendar, policy);
        diagnostics.connected();

        for (String at : List.of("06:13:50", "06:14:30", "06:17:00", "06:20:10", "06:30:00", "06:45:00", "06:46:00")) {
            when(calendar.now()).thenReturn(Instant.parse("2026-10-02T" + at + "Z"));
            recorder.poll();
            if (at.equals("06:14:30")) { diagnostics.droppedTick(); diagnostics.droppedTick(); }
        }

        verify(health, times(1)).recordToday(new RealtimeWindow(true, 2L,
                Instant.parse("2026-10-02T06:14:30Z"), Instant.parse("2026-10-02T06:20:10Z")));

        var restarted = new ClosingDailyHealthRecorder(health, diagnostics, calendar, policy);
        when(calendar.now()).thenReturn(Instant.parse("2026-10-02T06:20:10Z"));
        restarted.poll();
        assertThat(restarted.window()).isEqualTo(RealtimeWindow.UNOBSERVED);
    }
}
