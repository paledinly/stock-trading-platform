package com.sunmo.stockplatform.intraday;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.intraday.IntradayModel.*;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class IntradayEngineTest {
    static final Instant START = Instant.parse("2026-09-29T01:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    static IntradayProperties policy() {
        return policy(Map.of());
    }
    static IntradayProperties policy(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>(); values.put("intraday.enabled", "true");
        for (String name : List.of("buy-fee-percent", "sell-fee-percent", "sell-tax-percent", "spread-percent", "buy-slippage-percent", "sell-slippage-percent"))
            values.put("intraday.costs." + name, "0.001");
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values)).bind("intraday", IntradayProperties.class).orElseThrow(IllegalStateException::new);
    }
    static ClosingTradingCalendar calendar() {
        return new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null, null, null, null, List.of()),
                Clock.fixed(START.plusSeconds(1900), ZoneOffset.UTC));
    }
    static Input input(Instant at, double price, long volume, IntradayProperties policy) {
        BigDecimal p = BigDecimal.valueOf(price), value = BigDecimal.valueOf(volume).multiply(BigDecimal.valueOf(10000));
        MarketTick tick = new MarketTick("005930", at.atZone(IntradayEngine.ZONE).toLocalDate(), at, p, 1000, volume,
                value, volume, BigDecimal.valueOf(10000), p.max(BigDecimal.valueOf(10000)), BigDecimal.valueOf(9990),
                BigDecimal.valueOf(125), volume / 2, volume / 2, BigDecimal.valueOf(50), false, null, null);
        MarketFeatureSnapshot shared = new MarketFeatureSnapshot("005930", tick.businessDate(), at, p, volume, value,
                tick.openPrice(), tick.highPrice(), tick.lowPrice(), BigDecimal.valueOf(10000), null, null, null, null,
                tick.tradeStrength(), 1, 1, tick.buyRatio(), null, false, null, MarketFeatureSnapshot.VERSION);
        return new Input(new ObservedMarketTick(tick, shared, at.plusMillis(1)), at.plusMillis(2), "삼성전자", true, false, policy);
    }
    static List<Input> breakoutInputs() {
        var p = policy(); List<Input> rows = new ArrayList<>(); long volume = 1000000;
        for (int i = 0; i <= 1800; i++) {
            volume += i >= 1500 ? 2000 : 1000;
            rows.add(input(START.plusSeconds(i), i >= 1795 ? 10030 : i % 2 == 0 ? 10000 : 10001, volume, p));
        }
        return rows;
    }
    static Result signal() {
        IntradayEngine engine = new IntradayEngine(calendar()); breakoutInputs().forEach(engine::accept);
        assertThat(engine.results()).hasSize(1); return engine.results().iterator().next();
    }
    static Input after(Result r, long seconds, double price) {
        return input(r.signal().recommendedAt().plusSeconds(seconds), price, 10000000 + seconds * 1000, r.signal().policy());
    }

    @Test void featureWindowsUseCumulativeDeltasAndCommonVwap() {
        List<Input> rows = breakoutInputs(); Features f = IntradayFeatures.calculate(rows, rows.getLast());
        assertThat(f.value("volumeRatio5m")).isBetween(1.99, 2.01);
        assertThat(f.value("valueRatio5m")).isBetween(1.99, 2.01);
        assertThat(f.value("vwap")).isEqualTo(10000);
        assertThat(f.value("return1m")).isPositive();
        assertThat(f.marketRegime()).isEqualTo("UNAVAILABLE");
    }
    @Test void futureCandleHighVolumeAndLateReceivedDataCannotLeakIntoFeatures() {
        List<Input> rows = new ArrayList<>(breakoutInputs()); Input now = rows.getLast();
        Features before = IntradayFeatures.calculate(rows, now);
        rows.add(input(now.evaluatedAt().plusSeconds(1), 200000, 99999999, now.policy()));
        Input delayed = input(now.observation().tick().occurredAt(), 300000, 100000000, now.policy());
        rows.add(new Input(new ObservedMarketTick(delayed.observation().tick(), delayed.observation().feature(), now.evaluatedAt().plusSeconds(30)),
                now.evaluatedAt().plusSeconds(30), delayed.stockName(), true, false, now.policy()));
        assertThat(IntradayFeatures.calculate(rows, now)).isEqualTo(before);
    }
    @Test void candleWindowRequiresContinuousSameSessionHistory() {
        IntradayEngine engine = new IntradayEngine(calendar());
        var rows = breakoutInputs(); rows.stream().filter(x -> !x.observation().tick().occurredAt().equals(START.plusSeconds(900)))
                .filter(x -> x.observation().tick().occurredAt().isBefore(START.plusSeconds(900))
                        || !x.observation().tick().occurredAt().isBefore(START.plusSeconds(1000))).forEach(engine::accept);
        assertThat(engine.results()).isEmpty();
    }
    @Test void breakoutCreatesSeparateSignalWithStructuralPricesAndNetRiskReward() {
        Result result = signal(); var s = result.signal(); var plan = s.plan();
        assertThat(s.setup()).isEqualTo("BREAKOUT");
        assertThat(plan.stop()).isLessThan(plan.entryFrom());
        assertThat(plan.entryFrom()).isLessThanOrEqualTo(s.price());
        assertThat(plan.entryTo()).isGreaterThan(s.price());
        assertThat(plan.chaseLimit()).isGreaterThan(plan.entryTo());
        assertThat(plan.target1()).isGreaterThan(plan.entryTo());
        assertThat(plan.target2()).isGreaterThan(plan.target1());
        assertThat(plan.riskReward()).isGreaterThanOrEqualTo(s.policy().minRiskReward());
        assertThat(s.reasons()).isNotEmpty(); assertThat(s.risks()).contains("UNVALIDATED_RESEARCH_SCORE");
    }
    @Test void pullbackAndRebreakRequireOrderedImpulseContractionRecovery() {
        var base = new IntradaySetups.Segment(98, 100, 97, 99, 300);
        var impulse = new IntradaySetups.Segment(100, 110, 100, 109, 300);
        var pullback = new IntradaySetups.Segment(109, 109, 105, 106, 100);
        var recovery = new IntradaySetups.Segment(106, 109.5, 106, 109.5, 200);
        assertThat(IntradaySetups.detect(base, impulse, pullback, recovery, 104, policy()).setup()).isEqualTo("PULLBACK");
        var rebreak = new IntradaySetups.Segment(106, 111, 106, 111, 200);
        assertThat(IntradaySetups.detect(base, impulse, pullback, rebreak, 104, policy()).setup()).isEqualTo("RE_BREAKOUT");
        assertThat(IntradaySetups.detect(base, impulse, new IntradaySetups.Segment(109, 109, 95, 106, 100),
                recovery, 104, policy())).isNull();
    }
    @Test void badRiskRewardAndNonPositiveStopAreRejected() {
        assertThat(IntradaySetups.plan(new IntradaySetups.Detection("PULLBACK", 90, 101, 2, List.of()), 100, policy())).isNull();
        assertThat(IntradaySetups.plan(new IntradaySetups.Detection("BREAKOUT", 0, 100, 2, List.of()), 100, policy())).isNull();
    }
    @Test void sameSetupCannotRepeatEveryMinute() {
        var engine = new IntradayEngine(calendar()); breakoutInputs().forEach(engine::accept);
        Result r = engine.results().iterator().next();
        for (int i = 1; i <= 120; i++) engine.accept(after(r, i, 10030));
        assertThat(engine.results()).hasSize(1);
    }
    @Test void recommendationExpiresEvenWithoutSubsequentTicks() {
        Result r = signal(); IntradayTracker.advance(r, r.signal().expiresAt());
        assertThat(r.outcome().status).isEqualTo("EXPIRED");
        IntradayTracker.observe(r, after(r, 1, r.signal().price()));
        assertThat(r.outcome().entryAt).isNull();
    }
    @Test void cannotEnterOnRecommendationTickOrBeyondChaseLimit() {
        Result r = signal(); IntradayTracker.observe(r, after(r, 0, r.signal().price()));
        assertThat(r.outcome().entryAt).isNull();
        IntradayTracker.observe(r, after(r, 1, r.signal().plan().chaseLimit() + 1));
        assertThat(r.outcome().status).isEqualTo("INVALIDATED");
    }
    @Test void stopBeforeLaterTargetRemainsLossAndSnapshotIsImmutable() throws Exception {
        Result r = signal(); String before = JSON.writeValueAsString(r.signal());
        IntradayTracker.observe(r, after(r, 1, r.signal().price()));
        assertThat(r.outcome().status).isEqualTo("ENTERED");
        IntradayTracker.observe(r, after(r, 2, r.signal().plan().stop() - 1));
        IntradayTracker.observe(r, after(r, 3, r.signal().plan().target1() + 1));
        assertThat(r.outcome().status).isEqualTo("STOPPED"); assertThat(r.outcome().targetBeforeStop).isFalse();
        assertThat(r.outcome().netReturn).isNegative(); assertThat(r.outcome().mae).isNegative();
        assertThat(r.outcome().mfe).isPositive(); assertThat(JSON.writeValueAsString(r.signal())).isEqualTo(before);
    }
    @Test void targetBeforeLaterStopRemainsWin() {
        Result r = signal(); IntradayTracker.observe(r, after(r, 1, r.signal().price()));
        IntradayTracker.observe(r, after(r, 2, r.signal().plan().target1() + 1));
        IntradayTracker.observe(r, after(r, 3, r.signal().plan().stop() - 1));
        assertThat(r.outcome().status).isEqualTo("TARGET_HIT"); assertThat(r.outcome().targetBeforeStop).isTrue();
        assertThat(r.outcome().netReturn).isLessThan(r.outcome().grossReturn);
    }
    @Test void missingIntrabarOrderNeverBecomesAWin() {
        Result r = signal(); IntradayTracker.observe(r, after(r, 1, r.signal().price()));
        IntradayTracker.observe(r, after(r, 180, r.signal().plan().target1() + 1));
        assertThat(r.outcome().status).isEqualTo("UNRESOLVED"); assertThat(r.outcome().targetBeforeStop).isNull();
        assertThat(r.outcome().netReturn).isNull();
    }
    @Test void horizonsAreObservedAtDueTimeNotFilledWithLatestPrice() {
        Result r = signal();
        for (int i = 1; i <= 3600; i++) IntradayTracker.observe(r, after(r, i, r.signal().price()));
        assertThat(r.outcome().returns.keySet()).containsExactly(1, 3, 5, 10, 15, 30, 60);
        assertThat(r.outcome().returns.get(5).observedAt()).isEqualTo(r.signal().recommendedAt().plusSeconds(300));
        Result missing = signal(); IntradayTracker.observe(missing, after(missing, 90, missing.signal().price()));
        assertThat(missing.outcome().returns).doesNotContainKey(1);
    }
    @Test void tradingCostIncludesBothSidesSpreadSlippageAndTax() {
        var c = new IntradayProperties.Costs(0.1, 0.1, 0.2, 0.2, 0.1, 0.1);
        assertThat(c.buy(100)).isCloseTo(100.2, within(0.000001));
        assertThat(c.sell(100)).isCloseTo(99.8, within(0.000001));
        assertThat(c.net(c.buy(100), c.sell(100))).isCloseTo((99.8 * .997 / (100.2 * 1.001) - 1) * 100, within(0.000001));
        assertThat(new IntradayProperties.Costs(null, null, null, null, null, null).configured()).isFalse();
    }
    @Test void configuredHolidaysAndClosedSessionsAreNotEvaluated() {
        assertThat(calendar().isTradingDay(LocalDate.of(2026, 9, 24))).isFalse();
        assertThat(calendar().isTradingDay(LocalDate.of(2026, 9, 26))).isFalse();
        var engine = new IntradayEngine(calendar());
        assertThat(engine.accept(input(Instant.parse("2026-09-24T01:00:00Z"), 10000, 100000, policy()))).isNull();
        assertThat(engine.candidates()).isEmpty();
        assertThat(IntradayEngine.session(Instant.parse("2026-09-29T06:00:00Z"))).isNull();
    }
    @Test void replayAndLiveUseIdenticalSnapshotsAndOutcomes() throws Exception {
        var live = new IntradayEngine(calendar()); var replay = new IntradayEngine(calendar());
        List<Input> rows = new ArrayList<>(breakoutInputs()); rows.forEach(live::accept);
        Result r = live.results().iterator().next();
        rows.add(after(r, 1, r.signal().price())); rows.add(after(r, 2, r.signal().plan().stop() - 1));
        live.accept(rows.get(rows.size() - 2)); live.accept(rows.getLast());
        for (Input row : rows) replay.accept(JSON.readValue(JSON.writeValueAsString(row), Input.class));
        assertThat(JSON.writeValueAsString(replay.results())).isEqualTo(JSON.writeValueAsString(live.results()));
    }
    @Test void calibrationDoesNotInventProbabilitiesWithoutCompletedSamples() {
        var stats = IntradayAnalytics.stats(List.of(signal()));
        assertThat(stats.trades()).isZero(); assertThat(stats.winRate()).isNull(); assertThat(stats.confidence()).isEqualTo("UNVALIDATED");
    }
    @Test void researchTargetModesAndTrailingExitAreExplicit() {
        var d = new IntradaySetups.Detection("BREAKOUT", 9995, 10300, 10, List.of());
        for (int percent = 1; percent <= 3; percent++) {
            var p = policy(Map.of("intraday.target-mode", "FIXED_" + percent));
            Plan plan = IntradaySetups.plan(d, 10000, p);
            assertThat(plan.target1()).isCloseTo(plan.entryTo() * (1 + percent / 100.0), within(0.000001));
        }
        var engine = new IntradayEngine(calendar());
        var p = policy(Map.of("intraday.trailing-after-target", "true"));
        breakoutInputs().forEach(x -> engine.accept(new Input(x.observation(), x.evaluatedAt(), x.stockName(), true, false, p)));
        Result r = engine.results().iterator().next();
        IntradayTracker.observe(r, after(r, 1, r.signal().price()));
        IntradayTracker.observe(r, after(r, 2, r.signal().plan().target1() + 1));
        assertThat(r.outcome().status).isEqualTo("ENTERED");
        assertThat(r.outcome().targetBeforeStop).isTrue();
        IntradayTracker.observe(r, after(r, 3, r.outcome().trailingStop - 1));
        assertThat(r.outcome().status).isEqualTo("TRAILING_EXIT");
        assertThat(r.outcome().targetBeforeStop).isTrue();
    }
    @Test void strictRiskCoverageAndLegacyUnknownCostsProduceNoTrade() {
        for (var p : List.of(policy(Map.of("intraday.require-complete-risk-data", "true")),
                policy(Map.of("intraday.costs.sell-tax-percent", "", "intraday.version", "intraday-research-v1")))) {
            var engine = new IntradayEngine(calendar());
            breakoutInputs().forEach(x -> engine.accept(new Input(x.observation(), x.evaluatedAt(), x.stockName(), true, false, p)));
            assertThat(engine.results()).isEmpty();
        }
    }
    @Test void missingOrPartialCostsAllowRecommendationsAndGrossTrackingButNotNet() throws Exception {
        for (boolean allMissing : List.of(true, false)) {
            Map<String, Object> overrides = new HashMap<>();
            overrides.put("intraday.costs.sell-tax-percent", "");
            if (allMissing) for (String key : List.of("buy-fee-percent", "sell-fee-percent", "spread-percent",
                    "buy-slippage-percent", "sell-slippage-percent")) overrides.put("intraday.costs." + key, "");
            var p = policy(overrides);
            var engine = new IntradayEngine(calendar());
            var replay = new IntradayEngine(calendar());
            for (Input x : breakoutInputs()) {
                var input = new Input(x.observation(), x.evaluatedAt(), x.stockName(), true, false, p);
                engine.accept(input);
                replay.accept(JSON.readValue(JSON.writeValueAsString(input), Input.class));
            }
            assertThat(engine.results()).hasSize(1);
            assertThat(JSON.writeValueAsString(replay.results())).isEqualTo(JSON.writeValueAsString(engine.results()));
            Result r = engine.results().iterator().next();
            assertThat(r.signal().risks()).contains("COSTS_NOT_CONFIGURED");
            assertThat(r.signal().plan().riskReward()).isCloseTo(p.targetRiskMultiple(), within(0.000001));
            IntradayTracker.observe(r, after(r, 1, r.signal().price()));
            assertThat(r.outcome().entryPrice).isEqualTo(r.signal().price());
            IntradayTracker.observe(r, after(r, 2, r.signal().plan().target1() + 1));
            assertThat(r.outcome().status).isEqualTo("TARGET_HIT");
            assertThat(r.outcome().grossReturn).isPositive();
            assertThat(r.outcome().netReturn).isNull();
            assertThat(IntradayAnalytics.stats(List.of(r)).trades()).isZero();
            assertThat(IntradayAnalytics.stats(List.of(r)).averageNetReturn()).isNull();
        }
    }
    @Test void missingCloseAndCounterResetCannotFabricateExit() {
        Result r = signal(); IntradayTracker.observe(r, after(r, 1, r.signal().price()));
        Input reset = input(r.signal().recommendedAt().plusSeconds(2), r.signal().plan().target1() + 1, 1000, r.signal().policy());
        IntradayTracker.observe(r, reset);
        assertThat(r.outcome().status).isEqualTo("UNRESOLVED");
        assertThat(r.outcome().netReturn).isNull();
        Result noClose = signal(); IntradayTracker.observe(noClose, after(noClose, 1, noClose.signal().price()));
        IntradayTracker.advance(noClose, Instant.parse("2026-09-29T06:31:00Z"));
        assertThat(noClose.outcome().status).isEqualTo("UNRESOLVED");
        assertThat(noClose.outcome().closeReturn).isNull();
        assertThat(noClose.outcome().trackingComplete).isTrue();
    }
}
