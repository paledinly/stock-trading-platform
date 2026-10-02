package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

class InvestorFlowTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-10-02T06:00:00Z");
    private static Context row(String kind, String scope, Instant receipt, String value) {
        return new Context(kind, "005930", scope, receipt, new BigDecimal(value), null, null, "ESTIMATED_SOURCE_TIME_UNKNOWN", "SHARES");
    }
    @Test void estimatesPreserveSignedQuantityAndMissingIsNotZero() throws Exception {
        var rows = InvestorFlowCollector.parseEstimates(mapper.readTree("""
                [{"mksc_shrn_iscd":"005930","frgn_ntby_qty":"-1,200","orgn_ntby_qty":""},
                 {"mksc_shrn_iscd":"000660","frgn_ntby_qty":"900","orgn_ntby_qty":"800"}]
                """), Set.of("005930"), NOW);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().value()).isEqualByComparingTo("-1200");
        assertThat(rows.getFirst().sourceAt()).isNull();
        assertThat(rows.getFirst().status()).isEqualTo("ESTIMATED_SOURCE_TIME_UNKNOWN");
        assertThatThrownBy(() -> InvestorFlowCollector.parseEstimates(mapper.readTree("{}"), Set.of("005930"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void programUsesLatestNonFutureSourceRegardlessOfResponseOrder() throws Exception {
        var row = InvestorFlowCollector.parseProgram(mapper.readTree("""
                [{"bsop_hour":"145900","whol_smtn_ntby_qty":"100"},
                 {"bsop_hour":"150100","whol_smtn_ntby_qty":"999"},
                 {"bsop_hour":"145000","whol_smtn_ntby_qty":"-200"}]
                """), "005930", NOW);
        assertThat(row.value()).isEqualByComparingTo("100");
        assertThat(row.sourceAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(row.receivedAt()).isEqualTo(NOW);
        assertThat(InvestorFlowCollector.parseProgram(mapper.readTree("[]"), "005930", NOW)).isNull();
    }
    @Test void deltaRejectsFutureStaleOtherScopeAndPriorDayObservations() {
        var old = row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", NOW.minusSeconds(1800), "-100");
        var current = row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", NOW, "50");
        var future = row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", NOW.plusSeconds(1), "9999");
        var other = row("FOREIGN_NET_BUY", "KRX", NOW, "7777");
        var f = InvestorFlowFeatures.calculate("005930", List.of(future, old, other, current), NOW, NOW.plusSeconds(10), Duration.ofMinutes(6));
        assertThat((BigDecimal) f.get("foreignNetBuy30mDelta")).isEqualByComparingTo("150");
        assertThat(f.get("institutionNetBuyToday")).isNull();
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(old), NOW, NOW, Duration.ofMinutes(6)).get("foreignNetBuyToday")).isNull();
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(current), NOW, NOW, Duration.ofMinutes(6)).get("foreignNetBuy30mDelta")).isNull();
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(current), NOW.plusSeconds(86400), NOW.plusSeconds(86400), Duration.ofDays(2)).get("foreignNetBuyToday")).isNull();
        Context staleSource = new Context("PROGRAM_NET_BUY", "005930", "KRX", NOW, BigDecimal.TEN, null, NOW.minusSeconds(900), "INTRADAY", "SHARES");
        assertThat(InvestorFlowFeatures.calculate("005930", List.of(staleSource), NOW, NOW, Duration.ofMinutes(6)).get("programNetBuyToday")).isNull();
    }
    @Test void snapshotIncludesFlowsWithoutChangingScoresAndOldContextJsonStillLoads() throws Exception {
        var minute = TrajectoryFeaturesTest.row(0, "105", "100", "120");
        Instant cutoff = minute.start().plusSeconds(60);
        var base = TrajectoryFeatures.calculate("005930", "KOSPI", cutoff, cutoff.plusSeconds(2), List.of(minute), List.of(), List.of(), TrajectoryFeaturesTest.policy());
        var flows = List.of(row("FOREIGN_NET_BUY", "KIS_ESTIMATE_ALL", cutoff.minusSeconds(1), "30"));
        var enriched = TrajectoryFeatures.calculate("005930", "KOSPI", cutoff, cutoff.plusSeconds(2), List.of(minute), flows, List.of(), TrajectoryFeaturesTest.policy());
        assertThat(enriched.subScores()).isEqualTo(base.subScores());
        assertThat((BigDecimal) enriched.investorFlow().get("foreignNetBuyToday")).isEqualByComparingTo("30");
        Context old = mapper.readValue("""
                {"kind":"INDEX","symbol":"KOSPI","scope":"KOSPI","receivedAt":"2026-10-02T06:00:00Z","value":2500,"returnPct":1}
                """, Context.class);
        assertThat(old.sourceAt()).isNull();
    }
}
