/*
 * RealtimeCoverage(실시간 수신 연속성 장부) 테스트예요. 연결 끊김·장중 재시작만 빈 구간이 되고,
 * 처음·새로 구독한 종목과 장 마감 뒤 연결 쉬기는 빈 구간이 아닌지, 빈 구간에 걸친 칸만 일부 체결 봉으로 보는지 확인해요.
 */
package com.sunmo.stockplatform.market.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeCoverageTest {
    // 2026-10-16(금). 시각은 UTC로 적고 KST = UTC + 9시간이에요(00:35Z = 09:35 KST).
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table stock(id bigint primary key, stock_code varchar(12))");
        jdbc.execute("create table stock_candle(stock_id bigint, timeframe varchar(10), start_time timestamp with time zone, "
                + "source varchar(20), is_final boolean)");
        jdbc.update("insert into stock values (1, 'A'), (2, 'B')");
    }

    @AfterEach void close() { db.shutdown(); }

    static Instant at(String utc) { return Instant.parse(utc); }

    void realtimeBar(long stockId, String startUtc) {
        jdbc.update("insert into stock_candle values (?, '5M', ?, 'REALTIME', true)", stockId, Timestamp.from(at(startUtc)));
    }

    @Test
    void disconnectMarksBucketsUntilResubscribedAsPartial() {
        var coverage = new RealtimeCoverage(jdbc, at("2026-10-15T23:00:00Z"));
        coverage.subscribed("A", at("2026-10-15T23:59:00Z")); // 08:59 구독: 정상 시작이라 빈 구간 없음
        assertThat(coverage.partial("A", at("2026-10-16T00:00:00Z"))).isFalse();

        coverage.disconnected(at("2026-10-16T01:02:10Z")); // 10:02 끊김 → 10:00 칸부터 빈 구간
        assertThat(coverage.partial("A", at("2026-10-16T00:55:00Z"))).isFalse();
        assertThat(coverage.partial("A", at("2026-10-16T01:00:00Z"))).isTrue();
        assertThat(coverage.repairable()).isEmpty(); // 다시 구독되기 전에는 채우지 않아요

        coverage.subscribed("A", at("2026-10-16T01:07:30Z")); // 10:07:30 재구독
        assertThat(coverage.partial("A", at("2026-10-16T01:05:00Z"))).isTrue();
        assertThat(coverage.partial("A", at("2026-10-16T01:10:00Z"))).isFalse();
        var gap = coverage.repairable().getFirst();
        assertThat(gap.cause()).isEqualTo(RealtimeCoverage.Cause.DISCONNECT);
        assertThat(gap.lostFrom()).isEqualTo(at("2026-10-16T01:00:00Z"));
        assertThat(gap.lastBucket()).isEqualTo(at("2026-10-16T01:05:00Z"));
        assertThat(RealtimeGapRepairService.targets(gap)).hasSize(2);
    }

    @Test
    void restartGapStartsAfterTheLastRealtimeBarOfThePreviousProcess() {
        realtimeBar(1, "2026-10-16T00:25:00Z"); // 09:25 봉까지 이전 프로세스가 저장
        realtimeBar(2, "2026-10-16T00:05:00Z"); // B는 09:05 뒤로 실시간 봉이 없어요(재시작 전 구독 중이 아니었음)
        var coverage = new RealtimeCoverage(jdbc, at("2026-10-16T00:33:30Z")); // 09:33:30 프로세스 시작
        coverage.subscribed("A", at("2026-10-16T00:35:41Z"));
        coverage.subscribed("B", at("2026-10-16T00:35:41Z"));

        assertThat(coverage.partial("A", at("2026-10-16T00:35:00Z"))).isTrue();
        assertThat(coverage.partial("A", at("2026-10-16T00:40:00Z"))).isFalse();
        assertThat(coverage.partial("B", at("2026-10-16T00:35:00Z"))).isFalse();
        var gap = coverage.repairable().getFirst();
        assertThat(coverage.repairable()).hasSize(1);
        assertThat(gap.cause()).isEqualTo(RealtimeCoverage.Cause.RESTART);
        assertThat(gap.lostFrom()).isEqualTo(at("2026-10-16T00:30:00Z"));
        assertThat(RealtimeGapRepairService.targets(gap))
                .containsExactly(at("2026-10-16T00:30:00Z"), at("2026-10-16T00:35:00Z"));
    }

    @Test
    void newOrReturningSubscriptionsAndPlannedPausesAreNotGaps() {
        var coverage = new RealtimeCoverage(jdbc, at("2026-10-15T23:00:00Z"));
        coverage.subscribed("A", at("2026-10-16T01:02:00Z")); // 장중 처음 구독: 이전 실시간 봉 없음
        coverage.unsubscribed("A");
        coverage.subscribed("A", at("2026-10-16T02:00:00Z")); // 해지 뒤 다시 구독
        assertThat(coverage.partial("A", at("2026-10-16T01:00:00Z"))).isFalse();
        assertThat(coverage.partial("A", at("2026-10-16T01:55:00Z"))).isFalse();

        coverage.paused(); // 장 마감 뒤 연결 쉬기
        coverage.disconnected(at("2026-10-16T06:40:00Z")); // 15:40 끊김은 장 밖이라 무시
        assertThat(coverage.repairable()).isEmpty();
        assertThat(coverage.partial("A", at("2026-10-16T06:25:00Z"))).isFalse();
    }

    @Test
    void untrackedCoverageNeverReportsRestartGaps() {
        var coverage = RealtimeCoverage.untracked();
        coverage.subscribed("A", at("2026-10-16T00:35:41Z"));
        assertThat(coverage.partial("A", at("2026-10-16T00:35:00Z"))).isFalse();
    }
}
