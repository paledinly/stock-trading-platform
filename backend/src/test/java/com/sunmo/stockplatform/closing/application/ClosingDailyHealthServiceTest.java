/*
 * ClosingDailyHealthService와 ClosingDailyHealthRecorder(마감 전략 일일 건강 기록)를 확인하는 테스트예요.
 * 하루의 공식 run, 추천 종목 가상 진입, 다음 거래일 관측, 실시간 연결 상태를 모아 COMPLETE/DEGRADED/MISSING으로 판정하는지 봐요.
 * 이 판정이 틀리면 Forward 표본이 온전한 날과 빠진 날을 구분할 수 없어서 중요해요.
 */
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

/**
 * 건강검진 의사처럼 하루치 마감 전략 실행 기록을 보고 빠진 곳을 정확히 짚어내는지 확인해요.
 */
class ClosingDailyHealthServiceTest {
    // 시각은 UTC예요. KST로는 평가 완료 15:15:08, 가상 진입 마감 15:20, 점검 15:45(장 마감 15:30 + 15분)예요.
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    static final Instant COMPLETED = Instant.parse("2026-10-02T06:15:08Z");
    static final Instant DEADLINE = Instant.parse("2026-10-02T06:20:00Z");
    static final Instant CHECK = Instant.parse("2026-10-02T06:45:00Z");
    // 15:14:30~15:20:10(KST) 동안 연결이 유지되고 버린 틱이 0개인 정상 실시간 구간이에요.
    static final RealtimeWindow HEALTHY = new RealtimeWindow(true, 0L,
            Instant.parse("2026-10-02T06:14:30Z"), Instant.parse("2026-10-02T06:20:10Z"));
    final ClosingRecommendationProperties policy = new ClosingRecommendationProperties(20, 4, null, null, null, null, null);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    /** 매 테스트마다 새 H2(테스트용 메모리 DB)를 띄우고, 필요한 최소 테이블과 실제 마이그레이션(V30, V32)을 적용해요. */
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
                + "expected_session_date date, status varchar(20), candidate_source varchar(20))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__closing_paper_entry.sql"),
                new ClassPathResource("db/migration/V32__create_closing_daily_health.sql"),
                new ClassPathResource("db/migration/V39__split_daily_health_observations.sql")).execute(db);
    }
    /** 테스트가 끝나면 메모리 DB를 내려요. */
    @AfterEach void close() { db.shutdown(); }

    /** 고정 시계 Clock(항상 같은 시각을 돌려주는 시계)을 now에 맞춘 서비스를 만들어요. */
    ClosingDailyHealthService service(Instant now) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(now, ZoneOffset.UTC));
        return new ClosingDailyHealthService(jdbc, calendar, policy, new ObjectMapper());
    }

    /** run 7, 진입 마감 DEADLINE, Scanner 탐지 3건을 기본으로 둔 판정 입력(Facts)을 만들어요. */
    Facts facts(Instant completed, int selected, int entered, Map<String, Integer> observations, RealtimeWindow realtime) {
        return new Facts(7L, completed, DEADLINE, selected, entered, Map.of(), observations, Map.of(), realtime, 3, null);
    }

    /**
     * run, 진입, 관측, 실시간이 모두 온전할 때만 COMPLETE인지 확인해요.
     * 입력: 마감 전 완료된 run, 추천 2개 모두 진입, 관측 3건 완료, 정상 실시간 / 추천 0개(NO TRADE)인 날.
     * 기대: 둘 다 COMPLETE이고 사유 목록은 비어 있어요.
     */
    @Test void completeOnlyWhenRunEntriesObservationsAndRealtimeAreAllWhole() {
        assertThat(ClosingDailyHealthService.judge(facts(COMPLETED, 2, 2, Map.of("COMPLETED", 3), HEALTHY)))
                .isEqualTo(new Judgement(Verdict.COMPLETE, List.of()));
        assertThat(ClosingDailyHealthService.judge(facts(COMPLETED, 0, 0, Map.of(), HEALTHY)).verdict())
                .as("a NO TRADE day with a completed run is complete").isEqualTo(Verdict.COMPLETE);
    }

    /**
     * 완료된 공식 run이 없으면 MISSING인지 확인해요.
     * 입력: run 자체가 없는 경우 / run id는 있지만 완료시각이 없는 경우.
     * 기대: 각각 OFFICIAL_RUN_MISSING, OFFICIAL_RUN_INCOMPLETE 사유의 MISSING이에요.
     */
    @Test void missingWithoutACompletedOfficialRun() {
        assertThat(ClosingDailyHealthService.judge(new Facts(null, null, DEADLINE, 0, 0, Map.of(), Map.of(), Map.of(), HEALTHY, 0, null)))
                .isEqualTo(new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_MISSING")));
        assertThat(ClosingDailyHealthService.judge(facts(null, 0, 0, Map.of(), HEALTHY)))
                .isEqualTo(new Judgement(Verdict.MISSING, List.of("OFFICIAL_RUN_INCOMPLETE")));
    }

    /**
     * 빠진 곳마다 DEGRADED 사유가 하나씩 정해진 순서로 붙는지 확인해요.
     * 입력: 진입 마감 시각에야 끝난 평가, 추천 2개 중 1개만 진입, 관측 대기, 실시간 미관측 / 연결 끊김과 버린 틱 3개, 관측 데이터 누락.
     * 기대: 각 상황에 맞는 사유 코드가 모두 순서대로 나와요.
     */
    @Test void everyGapIsADegradedReason() {
        assertThat(ClosingDailyHealthService.judge(facts(DEADLINE, 2, 1, Map.of("COMPLETED", 1, "PENDING", 1),
                RealtimeWindow.UNOBSERVED)))
                .isEqualTo(new Judgement(Verdict.DEGRADED, List.of("EVALUATION_AFTER_ENTRY_DEADLINE",
                        "SELECTED_ENTRY_MISSING", "REALTIME_UNOBSERVED", "OBSERVATION_INCOMPLETE")));
        assertThat(ClosingDailyHealthService.judge(facts(COMPLETED, 1, 1, Map.of("DATA_MISSING", 1),
                new RealtimeWindow(false, 3L, HEALTHY.start(), HEALTHY.end()))).reasons())
                .containsExactly("REALTIME_DISCONNECTED", "REALTIME_TICKS_DROPPED", "OBSERVATION_INCOMPLETE");
    }

    /**
     * Broad 후보 관측의 결측은 판정에 쓰지 않는지 확인해요(실시간 구독·분봉 보완이 없어 원래 결측이 많아요).
     * 입력: 정밀 관측은 모두 완료, Broad는 결측·불완전. 기대: COMPLETE, 합계 건수에는 Broad도 들어가요.
     */
    @Test void broadObservationGapsDoNotDegradeTheDay() {
        Facts facts = new Facts(7L, COMPLETED, DEADLINE, 0, 0, Map.of(), Map.of("COMPLETED", 3),
                Map.of("DATA_MISSING", 4, "DATA_INCOMPLETE", 1), HEALTHY, 3, null);
        assertThat(ClosingDailyHealthService.judge(facts)).isEqualTo(new Judgement(Verdict.COMPLETE, List.of()));
        assertThat(facts.observationStatuses()).containsExactlyInAnyOrderEntriesOf(
                Map.of("COMPLETED", 3, "DATA_MISSING", 4, "DATA_INCOMPLETE", 1));
    }

    /**
     * 평가 구간에 Scanner 탐지가 0건이면 DEGRADED인지 확인해요.
     * 입력: 탐지 수 0인 경우 / 탐지 수를 세지 않은(null) 경우.
     * 기대: 0건은 SCANNER_DETECTIONS_MISSING, 세지 않은 경우는 빈틈으로 보지 않고 COMPLETE예요.
     */
    @Test void noScannerDetectionInTheEvaluationWindowIsDegraded() {
        assertThat(ClosingDailyHealthService.judge(new Facts(7L, COMPLETED, DEADLINE, 0, 0, Map.of(), Map.of(), Map.of(),
                HEALTHY, 0, null))).isEqualTo(new Judgement(Verdict.DEGRADED, List.of("SCANNER_DETECTIONS_MISSING")));
        assertThat(ClosingDailyHealthService.judge(new Facts(7L, COMPLETED, DEADLINE, 0, 0, Map.of(), Map.of(), Map.of(),
                HEALTHY, null, null)).verdict()).as("not counted is not a gap").isEqualTo(Verdict.COMPLETE);
    }

    /**
     * 저장된 DB 행으로 오늘 건강 기록을 만들고, 재시작 후에도 앞서 기록한 실시간 구간을 유지하는지 확인해요.
     * 입력: 어제·오늘 공식 FORWARD run, 오늘 REPLAY run, 추천 2개(1개만 진입), 어제 run의 오늘 관측 2건 완료, 어제 MISSING 기록.
     * 기대: 오늘은 run 7 기준 SELECTED_ENTRY_MISSING으로 DEGRADED, 재시작 후 미관측 실시간이 와도 기존 구간 유지, 어제 기록은 다시 쓰지 않아요.
     */
    @Test void recordsTodayFromStoredRowsAndKeepsEarlierRealtimeAfterRestart() {
        // 준비
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
        jdbc.update("insert into closing_candidate_observation values (1, 6, ?, 'COMPLETED', 'PRECISION'), "
                + "(2, 6, ?, 'COMPLETED', 'PRECISION'), (3, 8, ?, 'PENDING', 'PRECISION'), (4, 6, ?, 'DATA_MISSING', 'BROAD')",
                FRIDAY, FRIDAY, FRIDAY, FRIDAY);
        jdbc.update("insert into closing_daily_health(trade_date, verdict, reasons, strategy_version, checked_at) "
                + "values (?, 'MISSING', 'OFFICIAL_RUN_MISSING', 'closing-recommend-v9_1-1515', ?)",
                FRIDAY.minusDays(1), Timestamp.from(CHECK.minusSeconds(86400)));

        // 실행: 첫 기록
        DailyHealth first = service(CHECK).recordToday(HEALTHY, Stage.PRELIMINARY);

        // 확인(어서션, 기대값 검사): 035720은 추천이 아니라서 진입 상태 집계에만 들어가고 추천 진입 수에는 빠져요.
        // 관측은 오늘 세션을 기대하는 run 6(어제) 것만 세고, REPLAY run 8의 PENDING은 빠져요.
        assertThat(first.verdict()).isEqualTo(Verdict.DEGRADED);
        assertThat(first.reasons()).containsExactly("SELECTED_ENTRY_MISSING");
        assertThat(first.runId()).isEqualTo(7L);
        assertThat(first.runCompletedAt()).isEqualTo(COMPLETED);
        assertThat(first.selectedCount()).isEqualTo(2);
        assertThat(first.selectedEntered()).isEqualTo(1);
        assertThat(first.paperEntryStatuses()).containsExactlyInAnyOrderEntriesOf(
                Map.of("ENTERED", 2, "NO_FRESH_TICK_BEFORE_DEADLINE", 1));
        assertThat(first.precisionObservationStatuses()).containsExactlyEntriesOf(Map.of("COMPLETED", 2));
        assertThat(first.broadObservationStatuses()).as("broad gaps are kept but do not degrade")
                .containsExactlyEntriesOf(Map.of("DATA_MISSING", 1));
        assertThat(first.observationStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of("COMPLETED", 2, "DATA_MISSING", 1));
        assertThat(first.stage()).isEqualTo(Stage.PRELIMINARY);

        // 실행: 한 시간 뒤 재시작해 실시간 구간을 관측하지 못한 상태로 다시 기록
        DailyHealth restarted = service(CHECK.plusSeconds(3600)).recordToday(RealtimeWindow.UNOBSERVED, Stage.FINAL);
        assertThat(restarted.stage()).isEqualTo(Stage.FINAL);
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

    /**
     * 전략 버전이 바뀐 날에도 오늘 실제로 돈 공식 run을 찾는지 확인해요.
     * 입력: 이전 버전(v9) 공식 키 run 7, 같은 버전 수동 재시도 run 8, 현재 버전(v9_1) REPLAY run 9.
     * 기대: 공식 키를 가진 run 7이 선택되고, 전략 버전은 v9로 기록되며 OFFICIAL_RUN_MISSING이 없어요.
     */
    @Test void findsTheRunThatExecutedTodayAfterAVersionChange() {
        jdbc.update("insert into closing_recommendation_run values (7, ?, ?, 'FORWARD', 'closing-recommend-v9-1515', "
                + "'official-2026-10-02-closing-recommend-v9-1515')", FRIDAY, Timestamp.from(COMPLETED));
        jdbc.update("insert into closing_recommendation_run values (8, ?, ?, 'FORWARD', 'closing-recommend-v9-1515', "
                + "'manual-retry')", FRIDAY, Timestamp.from(COMPLETED.plusSeconds(30)));
        jdbc.update("insert into closing_recommendation_run values (9, ?, ?, 'REPLAY', 'closing-recommend-v9_1-1515', null)",
                FRIDAY, Timestamp.from(CHECK));

        DailyHealth row = service(CHECK).recordToday(HEALTHY, Stage.PRELIMINARY);

        assertThat(row.runId()).as("the official key of the earlier version wins over a later non-official run")
                .isEqualTo(7L);
        assertThat(row.strategyVersion()).isEqualTo("closing-recommend-v9-1515");
        assertThat(row.reasons()).doesNotContain("OFFICIAL_RUN_MISSING");
    }

    /**
     * 공식 run이 하나도 없으면 MISSING으로 기록하는지 확인해요.
     * 입력: 빈 DB.
     * 기대: 사유는 OFFICIAL_RUN_MISSING 하나뿐이고 탐지 누락은 중복 보고하지 않으며 추천 수는 0이에요.
     */
    @Test void recordsMissingWhenNoOfficialRunExists() {
        DailyHealth row = service(CHECK).recordToday(HEALTHY, Stage.PRELIMINARY);
        assertThat(row.verdict()).isEqualTo(Verdict.MISSING);
        assertThat(row.reasons()).as("a missing run is not also reported as missing detections")
                .doesNotContain("SCANNER_DETECTIONS_MISSING");
        assertThat(row.reasons()).containsExactly("OFFICIAL_RUN_MISSING");
        assertThat(row.selectedCount()).isZero();
    }

    /**
     * 기간 조회에서 기록 없는 거래일은 MISSING으로 채우고, 휴장일·주말·아직 점검 전인 오늘은 빼는지 확인해요.
     * 입력: 09-30 COMPLETE 기록 1건, 조회 기간 09-23~10-05, 점검 시각 15:45 / 그 10분 전 / 잘못된 기간.
     * 기대: 거래일만 최신순으로 나오고 미기록 날은 NO_HEALTH_RECORD, 15:35에는 오늘이 빠지며, 역순·400일 기간은 예외예요.
     */
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

    /**
     * 기록기(Recorder)가 입력 마감 전후 실시간 상태를 표본으로 잡고 거래일마다 한 번만 기록하는지 확인해요.
     * 입력: 15:13:50부터 15:46까지 여러 시각에 poll하고, 15:14:30 직후 틱 2개를 버린 상황이에요.
     * 기대: 15:14:30~15:20:10 구간, 버린 틱 2개로 recordToday가 정확히 1번 호출되고, 새로 만든 기록기는 미관측 상태예요.
     */
    @Test void recorderSamplesAroundFreezeAndRecordsOncePerTradingDay() {
        // 준비: 서비스와 달력은 목(mock, 가짜 객체)이에요.
        var health = mock(ClosingDailyHealthService.class);
        var calendar = mock(ClosingTradingCalendar.class);
        var diagnostics = new RealtimeDiagnostics();
        when(calendar.isTradingDay(FRIDAY)).thenReturn(true);
        when(calendar.close(FRIDAY)).thenReturn(Instant.parse("2026-10-02T06:30:00Z"));
        when(health.recordToday(any(), any())).thenReturn(DailyHealth.missing(FRIDAY));
        var recorder = new ClosingDailyHealthRecorder(health, diagnostics, calendar, policy);
        diagnostics.connected();

        for (String at : List.of("06:13:50", "06:14:30", "06:17:00", "06:20:10", "06:30:00", "06:45:00", "06:46:00")) {
            when(calendar.now()).thenReturn(Instant.parse("2026-10-02T" + at + "Z"));
            recorder.poll();
            if (at.equals("06:14:30")) { diagnostics.droppedTick(); diagnostics.droppedTick(); }
        }

        RealtimeWindow window = new RealtimeWindow(true, 2L,
                Instant.parse("2026-10-02T06:14:30Z"), Instant.parse("2026-10-02T06:20:10Z"));
        verify(health, times(1)).recordToday(window, Stage.PRELIMINARY);

        // 18:15 뒤 최종 기록을 한 번 더 남기고, 그 뒤로는 다시 기록하지 않아요.
        for (String at : List.of("09:14:30", "09:15:00", "09:20:00")) {
            when(calendar.now()).thenReturn(Instant.parse("2026-10-02T" + at + "Z"));
            recorder.poll();
        }
        verify(health, times(1)).recordToday(window, Stage.FINAL);
        verify(health, times(2)).recordToday(any(), any());

        var restarted = new ClosingDailyHealthRecorder(health, diagnostics, calendar, policy);
        when(calendar.now()).thenReturn(Instant.parse("2026-10-02T06:20:10Z"));
        restarted.poll();
        assertThat(restarted.window()).isEqualTo(RealtimeWindow.UNOBSERVED);
    }
}
