package com.sunmo.stockplatform.closing.trajectory;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryFeatures.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

class TrajectoryFeaturesTest {
    static final Instant START = Instant.parse("2026-09-30T05:30:00Z");
    static BigDecimal b(String value) { return new BigDecimal(value); }
    static TrajectoryProperties policy() {
        return new TrajectoryProperties(true, false, LocalTime.of(14, 30), LocalTime.of(15, 20), 5,
                Duration.ofSeconds(2), Duration.ofMinutes(6), b("1"), LocalTime.of(10, 30), b("3"), b("-2"), List.of(5, 15, 30), List.of(5, 15, 30));
    }
    static Minute row(int i, String price, String turnover, String strength) {
        BigDecimal p = b(price);
        return new Minute("005930", START.plusSeconds(i * 60L), START.plusSeconds(i * 60L + 59),
                START.plusSeconds(i * 60L + 59), START.plusSeconds(i * 60L + 62), "CLOSING_PHASE_1",
                p, p, p, p, b("100"), b(turnover), b("10000"), b("1000000"), b("100"), p.max(b("100")), b("90"),
                b(strength), b("60"), b("40"), true);
    }
    static Minute replace(Minute r, BigDecimal high, BigDecimal low, BigDecimal sell, boolean complete) {
        return new Minute(r.symbol(), r.start(), r.occurredAt(), r.receivedAt(), r.finalizedAt(), r.phase(), r.open(),
                high, low, r.close(), r.volume(), r.turnover(), r.dailyVolume(), r.dailyTurnover(), r.dayOpen(), high, r.dayLow(),
                r.executionStrength(), r.buyVolume(), sell, complete);
    }
    @Test void arithmeticHasExplicitZeroDenominatorsAndUnits() {
        assertThat(ratio(b("1000000"), b("10000"))).isEqualByComparingTo("100");
        assertThat(pct(b("150"), b("100"))).isEqualByComparingTo("50");
        assertThat(pct(b("10"), BigDecimal.ZERO)).isNull();
        assertThat(acceleration(b("25"), b("15"), b("10"))).isEqualByComparingTo("0.5");
        assertThat(rankChange(b("17"), b("55"))).isEqualByComparingTo("38");
        assertThat(range(b("109"), b("110"), b("100"))).isEqualByComparingTo("0.9");
        assertThat(range(b("100"), b("100"), b("100"))).isNull();
        assertThat(relative(b("4.2"), b("0.4"))).isEqualByComparingTo("3.8");
        assertThat(relative(b("4.2"), null)).isNull();
    }
    @Test void windowsCompareEqualDurationsAndExcludeFutureOrLateFinalizedData() {
        List<Minute> rows = new ArrayList<>();
        for (int i = 0; i < 61; i++) rows.add(row(i, "105", i < 30 ? "100" : i < 60 ? "200" : "999999", "120"));
        Instant cutoff = START.plusSeconds(3600), evaluated = cutoff.plusSeconds(2);
        Snapshot s = calculate("005930", "KOSPI", cutoff, evaluated, rows, List.of(), List.of(), policy());
        assertThat((BigDecimal) s.turnover().get("turnover30m")).isEqualByComparingTo("6000");
        assertThat((BigDecimal) s.turnover().get("turnover30mChangePct")).isEqualByComparingTo("100");
        assertThat((BigDecimal) s.volume().get("volume5mChangePct")).isZero();
        assertThat((BigDecimal) s.price().get("distanceFromHighPct")).isZero();
        assertThat(calculate("005930", "KOSPI", cutoff, cutoff, rows, List.of(), List.of(), policy())).isNull();
        rows.set(55, replace(rows.get(55), b("105"), b("105"), b("40"), false));
        assertThat(calculate("005930", "KOSPI", cutoff, evaluated, rows, List.of(), List.of(), policy()).turnover().get("turnover15m")).isNull();
    }
    @Test void oppositeExecutionTrajectoriesHaveOppositeSlopes() {
        List<Minute> up = List.of(row(0, "100", "100", "95"), row(1, "100", "100", "105"), row(2, "100", "100", "118"), row(3, "100", "100", "130"));
        List<Minute> down = List.of(row(0, "100", "100", "160"), row(1, "100", "100", "145"), row(2, "100", "100", "125"), row(3, "100", "100", "110"));
        assertThat(slope(up, Minute::executionStrength)).isPositive();
        assertThat(slope(down, Minute::executionStrength)).isNegative();
    }
    @Test void rankContextsRespectScopeReceiptAgeAndFutureBoundary() {
        List<Context> rows = List.of(new Context("TURNOVER_RANK", "005930", "ALL", START, b("80"), null),
                new Context("TURNOVER_RANK", "005930", "KOSPI", START.plusSeconds(60), b("1"), null),
                new Context("TURNOVER_RANK", "005930", "ALL", START.plusSeconds(61), b("17"), null));
        assertThat(context(rows, "TURNOVER_RANK", "005930", "ALL", START.plusSeconds(60), START.plusSeconds(60), Duration.ofMinutes(5)).value()).isEqualByComparingTo("80");
        assertThat(context(rows, "TURNOVER_RANK", "005930", "ALL", START.plusSeconds(600), START.plusSeconds(600), Duration.ofMinutes(5))).isNull();
    }
    @Test void detectsLateBreakoutAndDistribution() {
        List<Minute> breakout = new ArrayList<>(), distribution = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            breakout.add(row(i, Integer.toString(101 + i), i < 5 ? "100" : "200", "120"));
            Minute r = row(i, Integer.toString(110 - i), i < 5 ? "100" : "200", "100");
            distribution.add(replace(r, b("110"), r.low(), i < 5 ? b("20") : b("40"), true));
        }
        assertThat(patterns(breakout, b("1")).get("lateBreakout")).isEqualTo(true);
        assertThat(patterns(distribution, b("1")).get("distribution")).isEqualTo(true);
    }
    @Test void detectsFailedBreakoutWithLaterVwapLoss() {
        List<Minute> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var r = row(i, i < 7 ? "105" : Integer.toString(108 - i), "100", Integer.toString(140 - i * 5));
            rows.add(replace(r, i == 6 ? b("110") : b("105"), r.low(), b("40"), true));
        }
        rows.set(9, replace(row(9, "98", "100", "90"), b("105"), b("98"), b("40"), true));
        assertThat(patterns(rows, b("1")).get("failedBreakout")).isEqualTo(true);
    }
    @Test void recoveryRequiresLaterMinuteAndRisingVolume() {
        List<Minute> rows = new ArrayList<>(List.of(row(0, "110", "100", "120"), row(1, "107", "100", "110"), row(2, "110", "200", "130")));
        Minute r = rows.get(2);
        rows.set(2, new Minute(r.symbol(), r.start(), r.occurredAt(), r.receivedAt(), r.finalizedAt(), r.phase(), r.open(), r.high(), r.low(),
                r.close(), b("200"), r.turnover(), r.dailyVolume(), r.dailyTurnover(), r.dayOpen(), r.dayHigh(), r.dayLow(), r.executionStrength(), r.buyVolume(), r.sellVolume(), true));
        assertThat(patterns(rows, b("1")).get("pullbackRecovery")).isEqualTo(true);
        assertThat(patterns(rows.subList(0, 2), b("1")).get("pullbackRecovery")).isNull();
    }
    @Test void futureReceiptsAndRevisedDailyHistoryNeverEnterPastEvaluation() {
        Minute r = row(0, "105", "100", "120");
        var late = new Minute(r.symbol(), r.start(), r.occurredAt(), START.plusSeconds(70), r.finalizedAt(), r.phase(),
                r.open(), r.high(), r.low(), r.close(), r.volume(), r.turnover(), r.dailyVolume(), r.dailyTurnover(),
                r.dayOpen(), r.dayHigh(), r.dayLow(), r.executionStrength(), r.buyVolume(), r.sellVolume(), true);
        assertThat(calculate("005930", "KOSPI", START.plusSeconds(60), START.plusSeconds(62), List.of(late), List.of(), List.of(), policy())).isNull();
        var candle = new com.sunmo.stockplatform.candle.domain.StockCandle(null, "1D", START.minus(Duration.ofDays(1)),
                b("100"), b("100"), b("100"), b("100"), 100, b("10000"), true, 1,
                com.sunmo.stockplatform.candle.domain.CandleSource.BACKFILL);
        org.springframework.test.util.ReflectionTestUtils.setField(candle, "createdAt", START.minusSeconds(100));
        org.springframework.test.util.ReflectionTestUtils.setField(candle, "updatedAt", START.plusSeconds(70));
        var snapshot = calculate("005930", "KOSPI", START.plusSeconds(60), START.plusSeconds(62), List.of(r), List.of(), List.of(candle), policy());
        assertThat(snapshot.price().get("prevClose")).isNull();
    }
    @Test void belowVwapWithoutAnObservedCrossIsNotFailedBreakout() {
        List<Minute> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Minute r = row(i, Integer.toString(98 - i), "100", Integer.toString(140 - i * 5));
            rows.add(replace(r, i == 6 ? b("110") : b("105"), r.low(), b("40"), true));
        }
        assertThat(patterns(rows, b("1")).get("failedBreakout")).isEqualTo(false);
    }
}
