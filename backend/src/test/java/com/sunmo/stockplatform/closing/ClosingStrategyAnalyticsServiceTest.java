package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingStrategyAnalyticsService;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPerformanceRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClosingStrategyAnalyticsServiceTest {
    private final OvernightPerformanceRepository repository = mock(OvernightPerformanceRepository.class);
    private final ClosingTradingCalendar calendar = mock(ClosingTradingCalendar.class);
    private final ClosingStrategyAnalyticsService service = new ClosingStrategyAnalyticsService(
            repository, calendar, new ObjectMapper());

    @Test
    void calibratesOnlyRepositorySuppliedOfficialCompletedRows() {
        LocalDate to = LocalDate.of(2026, 9, 22);
        LocalDate from = to.minusDays(30);
        when(calendar.now()).thenReturn(Instant.parse("2026-09-22T06:30:00Z"));
        OvernightPerformance first = row(LocalDate.of(2026, 9, 1), "58", true, false, "1.2", "3.4", "-0.8", "0.5", "1");
        OvernightPerformance second = row(LocalDate.of(2026, 9, 10), "62", false, true, "-1.0", "0.8", "-3.2", "-0.4", "2");
        OvernightPerformance third = row(LocalDate.of(2026, 9, 15), "65", true, true, "-0.3", "3.1", "-3.5", "-0.1", "3");
        when(repository.findOfficialCompletedBetween(from, to, OvernightPerformance.OBSERVATION_VERSION))
                .thenReturn(List.of(first, second, third));

        var report = service.analyze(from, to);

        assertThat(report.sampleSize()).isEqualTo(3);
        assertThat(report.population()).isEqualTo("OFFICIAL_FORWARD_SIGNAL_PRICE_OBSERVATION_ONLY");
        assertThat(report.scoreBands()).extracting("band").containsExactly("50-59", "60-69");
        assertThat(report.scoreBands().get(1).targetHitRate()).isEqualByComparingTo("50");
        assertThat(report.scoreBands().get(1).confidenceLower95()).isNotNull();
        assertThat(report.lossPatterns()).filteredOn(item -> item.code().equals("STOP_HIT"))
                .singleElement().extracting("count").isEqualTo(2);
        assertThat(report.warnings()).contains("공식 전진 표본이 30건 미만이므로 결과는 탐색적으로만 해석하세요.");
        assertThat(report.warnings()).contains("신호가격 기준 시장 관측이며 실제 또는 모의 체결 순수익이 아닙니다.");
        assertThat(report.oosValidation().status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(report.oosValidation().splitDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(report.oosValidation().development().sampleSize()).isEqualTo(2);
        assertThat(report.oosValidation().validation().sampleSize()).isEqualTo(1);
        assertThat(report.oosValidation().developmentScoreMedian()).isEqualByComparingTo("60");
        assertThat(report.oosValidation().features()).extracting("feature").contains("vwapDistanceRate");
        var vwap = report.oosValidation().features().stream()
                .filter(item -> item.feature().equals("vwapDistanceRate")).findFirst().orElseThrow();
        assertThat(vwap.developmentMedian()).isEqualByComparingTo("1.5");
        assertThat(vwap.comparison().validationHigh().sampleSize()).isEqualTo(1);
    }

    @Test
    void rejectsReversedOrExcessiveRange() {
        LocalDate to = LocalDate.of(2026, 9, 22);
        assertThatThrownBy(() -> service.analyze(to, to.minusDays(1)))
                .hasMessageContaining("최대 730일");
        assertThatThrownBy(() -> service.analyze(to.minusDays(731), to))
                .hasMessageContaining("최대 730일");
    }

    @Test
    void flagsRecentDeteriorationOnlyAfterSeparateDateWindowsAreAvailable() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 9, 22);
        when(calendar.now()).thenReturn(Instant.parse("2026-09-22T06:30:00Z"));
        List<OvernightPerformance> rows = IntStream.range(0, 17).mapToObj(index -> {
            boolean baseline = index < 10;
            return row(from.plusDays(index), "60", baseline, !baseline,
                    baseline ? "1" : "-1", baseline ? "3" : "0.5",
                    baseline ? "-0.5" : "-3", baseline ? "0.2" : "-0.4", Integer.toString(index + 1));
        }).toList();
        when(repository.findOfficialCompletedBetween(from, to, OvernightPerformance.OBSERVATION_VERSION))
                .thenReturn(rows);

        var report = service.analyze(from, to);

        assertThat(report.monitoring().status()).isEqualTo("DEGRADED");
        assertThat(report.monitoring().baseline().sampleSize()).isEqualTo(10);
        assertThat(report.monitoring().recent().sampleSize()).isEqualTo(7);
        assertThat(report.monitoring().closeReturnDelta()).isEqualByComparingTo("-2");
        assertThat(report.monitoring().failureTrends()).filteredOn(item -> item.code().equals("CLOSE_LOSS"))
                .singleElement().extracting("recentRate").isEqualTo(new BigDecimal("100.000000"));
        assertThat(report.contextCoverage().marketRegimeAvailable()).isFalse();
        assertThat(report.contextCoverage().sectorHistoryAvailable()).isFalse();
        assertThat(report.promotionGate().currentStrategy()).isEqualTo("RULE_BASED_V8_BASELINE");
        assertThat(report.promotionGate().statisticalModelStatus()).isEqualTo("BLOCKED");
        assertThat(report.promotionGate().eventModelStatus()).isEqualTo("BLOCKED");
        assertThat(report.promotionGate().productionActivationAllowed()).isFalse();
        assertThat(report.promotionGate().checks()).filteredOn(check -> check.code().equals("NET_EXECUTION_OUTCOMES"))
                .singleElement().extracting("passed").isEqualTo(false);
    }

    private OvernightPerformance row(LocalDate date, String score, boolean target, boolean stop, String close,
            String maximum, String drawdown, String open, String vwapDistance) {
        OvernightPerformance performance = mock(OvernightPerformance.class);
        ClosingRecommendation recommendation = mock(ClosingRecommendation.class);
        when(performance.getRecommendation()).thenReturn(recommendation);
        when(recommendation.getRecommendationScore()).thenReturn(bd(score));
        when(recommendation.getRecommendationDate()).thenReturn(date);
        when(recommendation.getFeatureSnapshot()).thenReturn("{\"vwapDistanceRate\":" + vwapDistance + "}");
        when(recommendation.getCandidateSource()).thenReturn(ClosingCandidateSource.PRECISION);
        when(recommendation.getScannerType()).thenReturn(ScannerType.VOLUME);
        when(performance.isTargetHit()).thenReturn(target);
        when(performance.isStopHit()).thenReturn(stop);
        when(performance.getTargetRate()).thenReturn(bd("3"));
        when(performance.getCloseReturnRate()).thenReturn(bd(close));
        when(performance.getMaxReturnRate()).thenReturn(bd(maximum));
        when(performance.getMaxDrawdownRate()).thenReturn(bd(drawdown));
        when(performance.getOpenReturnRate()).thenReturn(bd(open));
        return performance;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
