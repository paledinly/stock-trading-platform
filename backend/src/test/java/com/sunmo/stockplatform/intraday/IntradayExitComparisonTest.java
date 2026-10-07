package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.intraday.IntradayModel.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.sunmo.stockplatform.intraday.IntradayEngineTest.*;
import static org.assertj.core.api.Assertions.*;

class IntradayExitComparisonTest {
    private Result paper() {
        var p = policy(Map.of("intraday.costs.spread-percent", "0", "intraday.costs.buy-slippage-percent", "0"));
        var s = new Signal(UUID.randomUUID(), "005930", "test", "BREAKOUT", "MID_SESSION", START, START,
                START, START.plusSeconds(300), 100, new Plan(99, 101, 103, 99, 102, 103, 2, "test"),
                80, new Features(Map.of("setupResistance", 99.5, "volumeRatio5m", 2., "valueRatio5m", 2.), "ABOVE", "UNKNOWN", List.of()), List.of(), List.of(), p);
        var r = new Result(s, new Outcome());
        IntradayTracker.observe(r, input(START.plusSeconds(1), 100, 1000, p));
        assertThat(r.outcome().entryPrice).isEqualTo(100);
        return r;
    }
    private IntradayExitComparison replay(Result paper, IntradayExitComparison.Variant variant) {
        var comparison = new IntradayExitComparison(List.of(paper), new IntradayExitComparison.Request(List.of(variant), null, START));
        comparison.accept(input(START.plusSeconds(1), 100, 1000, paper.signal().policy()));
        return comparison;
    }
    @SuppressWarnings("unchecked") private Result result(Map<String, Object> report, String name) {
        var groups = (Map<String, Object>) report.get("comparisons");
        return ((List<Result>) ((Map<String, Object>) groups.get(name)).get("results")).getFirst();
    }
    @Test void preservesEntryAndBaselineWhileWiderStopCanObserveLaterTarget() {
        var paper = paper(); var c = replay(paper, new IntradayExitComparison.Variant("WIDE", 2, 1, null, null));
        c.accept(input(START.plusSeconds(2), 98.5, 2000, paper.signal().policy()));
        c.accept(input(START.plusSeconds(3), 102, 3000, paper.signal().policy()));
        var report = c.finish(START.plusSeconds(4));
        assertThat(result(report, "BASELINE").outcome().status).isEqualTo("STOPPED");
        assertThat(result(report, "WIDE").outcome().status).isEqualTo("TARGET_HIT");
        assertThat(result(report, "WIDE").outcome().entryAt).isEqualTo(paper.outcome().entryAt);
        assertThat(paper.outcome().status).isEqualTo("ENTERED");
        assertThat(report.get("commonCompletedCount")).isEqualTo(1);
    }
    @Test void gapAfterBaselineExitInvalidatesOnlyStillOpenVariantAndExcludesPair() {
        var paper = paper(); var c = replay(paper, new IntradayExitComparison.Variant("WIDE", 2, 1, null, null));
        c.accept(input(START.plusSeconds(2), 98.5, 2000, paper.signal().policy()));
        c.accept(input(START.plusSeconds(100), 102, 3000, paper.signal().policy()));
        var report = c.finish(START.plusSeconds(101));
        assertThat(result(report, "BASELINE").outcome().uncertain).isFalse();
        assertThat(result(report, "WIDE").outcome().status).isEqualTo("UNRESOLVED");
        assertThat(report.get("commonCompletedCount")).isEqualTo(0);
    }
    @Test void protectionArmsAfterObservedRiseThenExitsOnLaterTick() {
        var paper = paper(); var c = replay(paper, new IntradayExitComparison.Variant("PROTECT", 1, 1, .5, .1));
        c.accept(input(START.plusSeconds(2), 101, 2000, paper.signal().policy()));
        c.accept(input(START.plusSeconds(3), 100.05, 3000, paper.signal().policy()));
        var report = c.finish(START.plusSeconds(4));
        assertThat(result(report, "PROTECT").outcome().status).isEqualTo("TRAILING_EXIT");
        assertThat(result(report, "PROTECT").outcome().grossReturn).isCloseTo(.05, within(.000001));
        assertThat(result(report, "BASELINE").outcome().status).isEqualTo("ENTERED");
    }
    @Test void missingEntryLedgerCannotUseSavedOutcomeAsSyntheticTick() {
        var paper = paper();
        var c = new IntradayExitComparison(List.of(paper), new IntradayExitComparison.Request(List.of(), null, null));
        c.accept(input(START.plusSeconds(2), 100, 2000, paper.signal().policy()));
        var o = result(c.finish(START.plusSeconds(3)), "BASELINE").outcome();
        assertThat(o.uncertain).isTrue();
        assertThat(o.quality).contains("ENTRY_NOT_REPRODUCIBLE");
    }
    @Test void commonCostsChangeNetWithoutChangingFrozenEntryOrExit() {
        var paper = paper(); var costs = new IntradayProperties.Costs(.1, .1, .2, .1, .1, .1);
        var c = new IntradayExitComparison(List.of(paper), new IntradayExitComparison.Request(List.of(), costs, null));
        c.accept(input(START.plusSeconds(1), 100, 1000, paper.signal().policy()));
        c.accept(input(START.plusSeconds(2), 102, 2000, paper.signal().policy()));
        var o = result(c.finish(START.plusSeconds(3)), "BASELINE").outcome();
        assertThat(o.entryPrice).isEqualTo(100);
        assertThat(o.netReturn).isCloseTo(costs.net(costs.buy(100), costs.sell(102)), within(.000001));
    }
    @Test void delayedInputCannotProduceOptimisticTarget() {
        var paper = paper(); var c = replay(paper, new IntradayExitComparison.Variant("SAME", 1, 1, null, null));
        var tick = input(START.plusSeconds(2), 102, 2000, paper.signal().policy());
        c.accept(new Input(tick.observation(), START.plusSeconds(30), tick.stockName(), true, false, tick.policy()));
        assertThat(result(c.finish(START.plusSeconds(31)), "SAME").outcome().status).isEqualTo("UNRESOLVED");
    }
    @Test void rejectsInvalidExperimentsAndPartialCosts() {
        assertThatThrownBy(() -> new IntradayExitComparison.Variant("X", Double.NaN, 1, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntradayExitComparison.Variant("X", 1, 1, .5, .6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntradayExitComparison.Request(List.of(), new IntradayProperties.Costs(null, 0., 0., 0., 0., 0.), null)).isInstanceOf(IllegalArgumentException.class);
    }
    private IntradayExitComparison.Variant variant(Map<String, Object> options) {
        return new IntradayExitComparison.Variant("RESEARCH", 1, 1, null, null,
                JSON.convertValue(options, IntradayExperimentOptions.class));
    }
    @Test void explicitPriceGridRoundsStopDownTargetUpAndWidensMinimumDistance() {
        var p = paper(); var c = replay(p, variant(Map.of("tickSizes", Map.of("005930", .3), "minimumStopPercent", 1.5)));
        var r = result(c.finish(START.plusSeconds(2)), "RESEARCH");
        assertThat(r.signal().plan().stop()).isCloseTo(98.4, within(.00001));
        assertThat(r.signal().plan().target1()).isCloseTo(102., within(.00001));
        assertThat(r.outcome().entryPrice).isEqualTo(100);
    }
    @Test void missingGridIsUnknownAndNarrowStopFilterIsNotCountedAsLoss() {
        var p = paper(); var c = replay(p, variant(Map.of("tickSizes", Map.of("000660", 1.))));
        assertThat(result(c.finish(START.plusSeconds(2)), "RESEARCH").outcome().quality).contains("PRICE_GRID_UNAVAILABLE");
        c = replay(p, variant(Map.of("minimumStopPercent", 2., "narrowStopMode", "FILTER")));
        var report = c.finish(START.plusSeconds(2));
        assertThat(report.get("mode")).isEqualTo("FIXED_CANDIDATE_ENTRY_EXIT_COMPARISON");
        assertThat(result(report, "RESEARCH").outcome().status).isEqualTo("FILTERED");
        assertThat(result(report, "RESEARCH").outcome().entryAt).isNull();
    }
    @Test void holdEntryUsesContinuousPastObservationsAndKeepsBaselineEntry() {
        var p = paper(); var c = replay(p, variant(Map.of("entryMode", "HOLD", "holdSeconds", 10)));
        c.accept(input(START.plusSeconds(5), 100, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(11), 100.5, 3000, p.signal().policy()));
        var report = c.finish(START.plusSeconds(12));
        assertThat(result(report, "BASELINE").outcome().entryPrice).isEqualTo(100);
        assertThat(result(report, "RESEARCH").outcome().entryPrice).isEqualTo(100.5);
        assertThat(result(report, "RESEARCH").outcome().entryAt).isEqualTo(START.plusSeconds(11).plusMillis(1));
    }
    @Test void lostInputDuringHoldCannotBecomeConfirmedEntry() {
        var p = paper(); var c = replay(p, variant(Map.of("entryMode", "HOLD", "holdSeconds", 10)));
        var i = input(START.plusSeconds(11), 100.5, 3000, p.signal().policy());
        c.accept(new Input(i.observation(), i.evaluatedAt(), i.stockName(), true, true, i.policy()));
        assertThat(result(c.finish(START.plusSeconds(12)), "RESEARCH").outcome().status).isEqualTo("UNRESOLVED");
    }
    @Test void retestRequiresAboveTouchAndLaterReclaim() {
        var p = paper(); var c = replay(p, variant(Map.of("entryMode", "RETEST", "retestTolerancePercent", .1)));
        c.accept(input(START.plusSeconds(2), 99.5, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(3), 100, 3000, p.signal().policy()));
        assertThat(result(c.finish(START.plusSeconds(4)), "RESEARCH").outcome().entryAt).isEqualTo(START.plusSeconds(3).plusMillis(1));
    }
    @Test void partialProfitAndRemainingStopAreWeightedAndCosted() {
        var p = paper(); var c = replay(p, variant(Map.of("partialAtPercent", .5, "partialFraction", .5)));
        c.accept(input(START.plusSeconds(2), 101, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(3), 99, 3000, p.signal().policy()));
        var o = result(c.finish(START.plusSeconds(4)), "RESEARCH").outcome();
        assertThat(o.grossReturn).isCloseTo(0., within(.000001));
        var costs = p.signal().policy().costs();
        assertThat(o.netReturn).isCloseTo((costs.net(100, costs.sell(101)) + costs.net(100, costs.sell(99))) / 2, within(.000001));
        assertThat(o.status).isEqualTo("STOPPED");
    }
    @Test void gapAfterPartialProfitDoesNotClaimEntirePositionWasProfitable() {
        var p = paper(); var c = replay(p, variant(Map.of("partialAtPercent", .5, "partialFraction", .5)));
        c.accept(input(START.plusSeconds(2), 101, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(100), 102, 3000, p.signal().policy()));
        var o = result(c.finish(START.plusSeconds(101)), "RESEARCH").outcome();
        assertThat(o.status).isEqualTo("UNRESOLVED"); assertThat(o.grossReturn).isNull();
    }
    @Test void trailingAfterPartialUsesNextTickAndNeverMovesDown() {
        var p = paper(); var c = replay(p, variant(Map.of("partialAtPercent", .5, "partialFraction", .5, "trailingDistancePercent", .3)));
        c.accept(input(START.plusSeconds(2), 101, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(3), 101.5, 3000, p.signal().policy()));
        c.accept(input(START.plusSeconds(4), 101, 4000, p.signal().policy()));
        var o = result(c.finish(START.plusSeconds(5)), "RESEARCH").outcome();
        assertThat(o.status).isEqualTo("TRAILING_EXIT"); assertThat(o.grossReturn).isCloseTo(1., within(.000001));
    }
    private Result later(Result original) {
        var s = original.signal(); var at = START.plusSeconds(10);
        var r = new Result(new Signal(UUID.randomUUID(), s.stockCode(), s.stockName(), s.setup(), s.session(), at, at, at,
                at.plusSeconds(300), s.price(), s.plan(), s.score(), s.features(), s.reasons(), s.risks(), s.policy()), new Outcome());
        IntradayTracker.observe(r, input(START.plusSeconds(11), 100, 11000, s.policy()));
        return r;
    }
    @SuppressWarnings("unchecked") private List<Result> results(Map<String, Object> report, String name) {
        return (List<Result>) ((Map<String, Object>) ((Map<String, Object>) report.get("comparisons")).get(name)).get("results");
    }
    @Test void stoppedSymbolReentryIsFilteredUsingOnlyAlreadyObservedStops() {
        var p = paper(); var later = later(p);
        var c = new IntradayExitComparison(List.of(later, p), new IntradayExitComparison.Request(
                List.of(variant(Map.of("maxStoppedPerSymbol", 1))), null, null));
        c.accept(input(START.plusSeconds(1), 100, 1000, p.signal().policy()));
        c.accept(input(START.plusSeconds(2), 99, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(11), 100, 11000, p.signal().policy()));
        var report = c.finish(START.plusSeconds(12));
        assertThat(results(report, "RESEARCH").get(1).outcome().quality).contains("REENTRY_STOP_LIMIT");
        assertThat(results(report, "BASELINE").get(1).outcome().status).isEqualTo("ENTERED");
    }
    @Test void reentryRequiresNewStructureAndFlowRatherThanOnlyCooldown() {
        var p = paper(); var later = later(p);
        var c = new IntradayExitComparison(List.of(p, later), new IntradayExitComparison.Request(
                List.of(variant(Map.of("requireFreshStructureAfterStop", true))), null, null));
        c.accept(input(START.plusSeconds(1), 100, 1000, p.signal().policy()));
        c.accept(input(START.plusSeconds(2), 99, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(11), 100, 11000, p.signal().policy()));
        assertThat(results(c.finish(START.plusSeconds(12)), "RESEARCH").get(1).outcome().quality)
                .contains("REENTRY_STRUCTURE_OR_FLOW_NOT_RENEWED");
    }
    @Test void invalidPartialAndHoldInputsAreRejected() {
        assertThatThrownBy(() -> variant(Map.of("partialAtPercent", 1.))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> variant(Map.of("partialAtPercent", 1., "partialFraction", 1.))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> variant(Map.of("entryMode", "HOLD"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldJsonRequestsRemainCompatibleAndNoOptionsDoNotChangeEntryMode() throws Exception {
        var request = JSON.readValue("""
                {"variants":[{"name":"OLD","stopDistanceMultiplier":1,"targetDistanceMultiplier":1}]}
                """, IntradayExitComparison.Request.class);
        assertThat(request.variants().getFirst().options().changesEntry()).isFalse();
        assertThat(request.variants().getFirst().options().tickSizes()).isEmpty();
    }
    @Test void futureSavedStopOutcomeCannotBlockEarlierCandidate() {
        var p = paper(); var later = later(p);
        // Stored final outcome is deliberately a stop, but replay hasn't observed that stop yet.
        p.outcome().status = "STOPPED"; p.outcome().exitAt = START.plusSeconds(20);
        var c = new IntradayExitComparison(List.of(p, later), new IntradayExitComparison.Request(
                List.of(variant(Map.of("maxStoppedPerSymbol", 1))), null, null));
        c.accept(input(START.plusSeconds(1), 100, 1000, p.signal().policy()));
        c.accept(input(START.plusSeconds(2), 102, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(11), 100, 11000, p.signal().policy()));
        assertThat(results(c.finish(START.plusSeconds(12)), "RESEARCH").get(1).outcome().status).isEqualTo("ENTERED");
    }
    @Test void strongerNewStructurePermitsReentryAfterStop() {
        var p = paper(); var later = later(p); var s = later.signal();
        later = new Result(new Signal(s.id(), s.stockCode(), s.stockName(), s.setup(), s.session(), s.sourceAt(), s.receivedAt(),
                s.recommendedAt(), s.expiresAt(), s.price(), s.plan(), s.score(),
                new Features(Map.of("setupResistance", 99.7, "volumeRatio5m", 3., "valueRatio5m", 3.), "ABOVE", "UNKNOWN", List.of()),
                s.reasons(), s.risks(), s.policy()), later.outcome());
        var c = new IntradayExitComparison(List.of(p, later), new IntradayExitComparison.Request(
                List.of(variant(Map.of("requireFreshStructureAfterStop", true))), null, null));
        c.accept(input(START.plusSeconds(1), 100, 1000, p.signal().policy()));
        c.accept(input(START.plusSeconds(2), 99, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(11), 100, 11000, p.signal().policy()));
        assertThat(results(c.finish(START.plusSeconds(12)), "RESEARCH").get(1).outcome().status).isEqualTo("ENTERED");
    }
    @Test void missedWinnerIsReportedWhenConfirmationNeverEnters() {
        var p = paper(); var c = replay(p, variant(Map.of("entryMode", "HOLD", "holdSeconds", 30)));
        c.accept(input(START.plusSeconds(2), 102, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(3), 104, 3000, p.signal().policy()));
        var report = c.finish(START.plusSeconds(4));
        @SuppressWarnings("unchecked") var groups = (Map<String, Map<String, Object>>) report.get("comparisons");
        assertThat(groups.get("RESEARCH").get("missedBaselineWinners")).isEqualTo(1L);
        assertThat(result(report, "RESEARCH").outcome().status).isEqualTo("INVALIDATED");
    }
    @Test void missingReentryFlowIsUnknownNotAConfirmedFilter() {
        var p = paper(); var next = later(p); var s = next.signal();
        next = new Result(new Signal(s.id(), s.stockCode(), s.stockName(), s.setup(), s.session(), s.sourceAt(), s.receivedAt(),
                s.recommendedAt(), s.expiresAt(), s.price(), s.plan(), s.score(),
                new Features(Map.of(), "ABOVE", "UNKNOWN", List.of()), s.reasons(), s.risks(), s.policy()), next.outcome());
        var c = new IntradayExitComparison(List.of(p, next), new IntradayExitComparison.Request(
                List.of(variant(Map.of("requireFreshStructureAfterStop", true))), null, null));
        c.accept(input(START.plusSeconds(1), 100, 1000, p.signal().policy()));
        c.accept(input(START.plusSeconds(2), 99, 2000, p.signal().policy()));
        c.accept(input(START.plusSeconds(11), 100, 11000, p.signal().policy()));
        var o = results(c.finish(START.plusSeconds(12)), "RESEARCH").get(1).outcome();
        assertThat(o.uncertain).isTrue(); assertThat(o.quality).contains("REENTRY_REFERENCE_UNAVAILABLE");
    }
}
