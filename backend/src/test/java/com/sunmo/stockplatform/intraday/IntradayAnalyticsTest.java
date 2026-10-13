package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.intraday.IntradayModel.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.sunmo.stockplatform.intraday.IntradayEngineTest.*;
import static org.assertj.core.api.Assertions.*;

class IntradayAnalyticsTest {
    private Result trade(boolean targetFirst, double stop, double target, int minute) {
        var s = new Signal(UUID.randomUUID(), "005930", "test", "BREAKOUT", "MID_SESSION", START, START, START,
                START.plusSeconds(300), 100, new Plan(99, 101, 103, stop, target, target + 1, 2, "test"), 80,
                new Features(Map.of(), "ABOVE", "UNKNOWN", List.of()), List.of(), List.of(), policy());
        var o = new Outcome();
        o.status = targetFirst ? "TARGET_HIT" : "STOPPED"; o.targetBeforeStop = targetFirst;
        o.entryReferencePrice = 100.; o.entryPrice = 100.05; o.entryAt = START.plusSeconds(60);
        o.exitAt = START.plusSeconds(60L * minute); o.netReturn = targetFirst ? 1.6 : -1.4;
        o.tradeMfe = 0.; o.tradeMae = 0.; o.holdingMinutes = 1.;
        return new Result(s, o);
    }

    @Test void targetEdgeSubtractsRandomWalkBaselineFromObservedRate() {
        // Stop 1 below and target 2 above the raw entry: a driftless walk hits the target first 1/3 of the time.
        var stats = IntradayAnalytics.stats(List.of(trade(true, 99, 102, 2), trade(false, 99, 102, 3)));
        assertThat(stats.targetBeforeStopRate()).isCloseTo(50., within(1e-9));
        assertThat(stats.randomBaselineTargetRate()).isCloseTo(100. / 3, within(1e-9));
        assertThat(stats.targetEdge()).isCloseTo(50. - 100. / 3, within(1e-9));
    }

    @Test void unusableGeometryIsLeftOutOfBothSidesOfTheEdge() {
        var stats = IntradayAnalytics.stats(List.of(trade(false, 99, 102, 2), trade(true, 101, 102, 3)));
        assertThat(stats.targetBeforeStopRate()).isCloseTo(50., within(1e-9));
        assertThat(stats.randomBaselineTargetRate()).isCloseTo(100. / 3, within(1e-9));
        assertThat(stats.targetEdge()).isCloseTo(-100. / 3, within(1e-9));
        assertThat(IntradayAnalytics.stats(List.of()).targetEdge()).isNull();
    }
}
