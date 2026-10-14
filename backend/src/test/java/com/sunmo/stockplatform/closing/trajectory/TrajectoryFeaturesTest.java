/*
 * TrajectoryFeatures(장 마감 전 1분 궤적 Feature 계산)를 검사하는 테스트예요.
 * 비율·변화율 계산의 0 분모 처리, 같은 길이 구간 비교, 늦게 수신·확정된 데이터 제외, 돌파·분산·회복 같은 패턴 판정을 확인해요.
 * 평가 시각 이후에 알게 된 정보가 과거 평가에 섞이지 않아야(point-in-time) 결과를 믿을 수 있어서 중요해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryFeatures.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/**
 * 시험 감독관처럼 궤적 Feature가 평가 시각까지 실제로 받은 데이터만으로 계산되는지 확인하는 테스트 모음이에요.
 */
class TrajectoryFeaturesTest {
    /** 첫 1분봉 시작시각이에요. 05:30Z는 한국 시간 14:30이에요. */
    static final Instant START = Instant.parse("2026-09-30T05:30:00Z");
    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    static BigDecimal b(String value) { return new BigDecimal(value); }
    /** 14:30~15:20 관측, 수신 유예 2초, 컨텍스트 최대 나이 6분 등 테스트용 궤적 설정을 만들어요. */
    static TrajectoryProperties policy() {
        return new TrajectoryProperties(true, false, LocalTime.of(14, 30), LocalTime.of(15, 20), 5,
                Duration.ofSeconds(2), Duration.ofMinutes(6), b("1"), LocalTime.of(10, 30), b("3"), b("-2"), List.of(5, 15, 30), List.of(5, 15, 30));
    }
    /** i번째 1분봉을 만들어요. 발생·수신은 분 끝(+59초), 확정은 +62초이고 시가·고가·저가·종가가 모두 같은 가격이에요. */
    static Minute row(int i, String price, String turnover, String strength) {
        BigDecimal p = b(price);
        return new Minute("005930", START.plusSeconds(i * 60L), START.plusSeconds(i * 60L + 59),
                START.plusSeconds(i * 60L + 59), START.plusSeconds(i * 60L + 62), "CLOSING_PHASE_1",
                p, p, p, p, b("100"), b(turnover), b("10000"), b("1000000"), b("100"), p.max(b("100")), b("90"),
                b(strength), b("60"), b("40"), true);
    }
    /** 기존 1분봉에서 고가·저가·매도량·완결 여부만 바꾼 복사본을 만들어요. 당일 고가도 같은 high로 바뀌어요. */
    static Minute replace(Minute r, BigDecimal high, BigDecimal low, BigDecimal sell, boolean complete) {
        return new Minute(r.symbol(), r.start(), r.occurredAt(), r.receivedAt(), r.finalizedAt(), r.phase(), r.open(),
                high, low, r.close(), r.volume(), r.turnover(), r.dailyVolume(), r.dailyTurnover(), r.dayOpen(), high, r.dayLow(),
                r.executionStrength(), r.buyVolume(), sell, complete);
    }
    /**
     * 비율·퍼센트·가속도·순위 변화·범위·상대값 계산의 단위와 0 분모 처리를 확인해요.
     * 입력: 직접 넣은 숫자 쌍. 기대: 정상 값은 정확히 계산되고, 분모가 0이거나 비교값이 없으면 null이어야 해요.
     */
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
    /**
     * 같은 길이 구간끼리 비교하고, 기준시각 이후 봉이나 미완결 봉은 계산에 넣지 않는지 확인해요.
     * 준비: 61개 1분봉(앞 30분 거래대금 100, 다음 30분 200, 기준시각에 시작하는 마지막 봉은 999999).
     * 기대: 최근 30분 거래대금 6000, 직전 30분 대비 +100%. 평가시각이 기준시각과 같으면(유예 부족) null,
     * 최근 15분 안에 미완결 봉이 있으면 turnover15m은 null이어야 해요.
     */
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
    /**
     * 체결강도가 오르는 궤적과 내리는 궤적의 기울기 부호가 반대인지 확인해요.
     * 입력: 체결강도 95→130 상승 4개 봉, 160→110 하락 4개 봉. 기대: 상승은 양수, 하락은 음수 기울기예요.
     */
    @Test void oppositeExecutionTrajectoriesHaveOppositeSlopes() {
        List<Minute> up = List.of(row(0, "100", "100", "95"), row(1, "100", "100", "105"), row(2, "100", "100", "118"), row(3, "100", "100", "130"));
        List<Minute> down = List.of(row(0, "100", "100", "160"), row(1, "100", "100", "145"), row(2, "100", "100", "125"), row(3, "100", "100", "110"));
        assertThat(slope(up, Minute::executionStrength)).isPositive();
        assertThat(slope(down, Minute::executionStrength)).isNegative();
    }
    /**
     * 순위 컨텍스트가 범위(ALL/KOSPI), 수신 나이, 미래 경계를 지키는지 확인해요.
     * 입력: ALL 80위(START), KOSPI 1위(+60초), ALL 17위(+61초, 평가 시각 이후).
     * 기대: +60초 평가에서는 범위가 다른 값과 미래 값을 빼고 80을 써요. +600초 평가에서는 5분보다 오래돼 null이에요.
     */
    @Test void rankContextsRespectScopeReceiptAgeAndFutureBoundary() {
        List<Context> rows = List.of(new Context("TURNOVER_RANK", "005930", "ALL", START, b("80"), null),
                new Context("TURNOVER_RANK", "005930", "KOSPI", START.plusSeconds(60), b("1"), null),
                new Context("TURNOVER_RANK", "005930", "ALL", START.plusSeconds(61), b("17"), null));
        assertThat(context(rows, "TURNOVER_RANK", "005930", "ALL", START.plusSeconds(60), START.plusSeconds(60), Duration.ofMinutes(5)).value()).isEqualByComparingTo("80");
        assertThat(context(rows, "TURNOVER_RANK", "005930", "ALL", START.plusSeconds(600), START.plusSeconds(600), Duration.ofMinutes(5))).isNull();
    }
    /**
     * 장 후반 돌파(lateBreakout)와 분산(distribution, 고점 근처에서 매도 우위로 밀리는 모양) 패턴을 찾는지 확인해요.
     * 입력: 가격이 101→110으로 오르며 후반 거래대금이 늘어난 10개 봉, 110→101로 내리며 후반 매도량이 늘어난 10개 봉.
     * 기대: 각각 lateBreakout, distribution이 true예요.
     */
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
    /**
     * 고점 돌파 뒤 VWAP(거래량 가중 평균가) 아래로 내려간 실패 돌파(failedBreakout)를 찾는지 확인해요.
     * 입력: 105 유지 후 6번째 봉 고가 110, 이후 가격과 체결강도가 내려가 마지막 봉이 98로 끝나는 10개 봉.
     * 기대: failedBreakout이 true예요.
     */
    @Test void detectsFailedBreakoutWithLaterVwapLoss() {
        List<Minute> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var r = row(i, i < 7 ? "105" : Integer.toString(108 - i), "100", Integer.toString(140 - i * 5));
            rows.add(replace(r, i == 6 ? b("110") : b("105"), r.low(), b("40"), true));
        }
        rows.set(9, replace(row(9, "98", "100", "90"), b("105"), b("98"), b("40"), true));
        assertThat(patterns(rows, b("1")).get("failedBreakout")).isEqualTo(true);
    }
    /**
     * 눌림 후 회복(pullbackRecovery)은 이후 분봉과 늘어난 거래량이 있어야 인정되는지 확인해요.
     * 입력: 110→107→110 세 봉, 마지막 봉 거래량 200. 기대: 세 봉이면 true, 앞 두 봉만 있으면 판정하지 않아 null이에요.
     */
    @Test void recoveryRequiresLaterMinuteAndRisingVolume() {
        List<Minute> rows = new ArrayList<>(List.of(row(0, "110", "100", "120"), row(1, "107", "100", "110"), row(2, "110", "200", "130")));
        Minute r = rows.get(2);
        rows.set(2, new Minute(r.symbol(), r.start(), r.occurredAt(), r.receivedAt(), r.finalizedAt(), r.phase(), r.open(), r.high(), r.low(),
                r.close(), b("200"), r.turnover(), r.dailyVolume(), r.dailyTurnover(), r.dayOpen(), r.dayHigh(), r.dayLow(), r.executionStrength(), r.buyVolume(), r.sellVolume(), true));
        assertThat(patterns(rows, b("1")).get("pullbackRecovery")).isEqualTo(true);
        assertThat(patterns(rows.subList(0, 2), b("1")).get("pullbackRecovery")).isNull();
    }
    /**
     * 평가 시각 이후에 수신된 분봉과, 평가 이후 수정된 일봉 이력이 과거 평가에 들어가지 않는지 확인해요.
     * 입력: 수신시각이 평가(+62초)보다 늦은 +70초인 분봉, updatedAt이 +70초인 전일 일봉(1D, BACKFILL).
     * 기대: 늦게 받은 분봉만 있으면 결과가 null이고, 수정된 일봉은 무시돼 prevClose가 null이에요.
     */
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
    /**
     * 처음부터 VWAP 아래에 있어 위에서 아래로 내려가는 교차가 관측되지 않았다면 실패 돌파가 아닌지 확인해요.
     * 입력: 98부터 계속 내려가는 10개 봉. 기대: failedBreakout이 false예요.
     */
    @Test void belowVwapWithoutAnObservedCrossIsNotFailedBreakout() {
        List<Minute> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Minute r = row(i, Integer.toString(98 - i), "100", Integer.toString(140 - i * 5));
            rows.add(replace(r, i == 6 ? b("110") : b("105"), r.low(), b("40"), true));
        }
        assertThat(patterns(rows, b("1")).get("failedBreakout")).isEqualTo(false);
    }
}
