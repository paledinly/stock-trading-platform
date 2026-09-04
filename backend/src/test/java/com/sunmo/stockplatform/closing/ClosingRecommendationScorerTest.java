package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer;
import com.sunmo.stockplatform.closing.application.DailyMovingAverageFeature;
import com.sunmo.stockplatform.closing.application.IntradayMovingAverageFeature;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClosingRecommendationScorerTest {
    private final ClosingRecommendationScorer scorer =
            new ClosingRecommendationScorer(new ObjectMapper().findAndRegisterModules());

    @Test
    void rewardsClosingStrengthAndLiquidity() {
        var weak = detection("2026-09-04T05:31:00Z", "25", "15", "1.2", "100000000", "0.5",
                "{\"vwapDistanceRate\":0.2,\"dayHighDistanceRate\":4.5,\"tradeStrength\":92}");
        var strong = detection("2026-09-04T06:20:00Z", "75", "10", "3.2", "12000000000", "4.5",
                "{\"vwapDistanceRate\":2.4,\"dayHighDistanceRate\":0.4,\"tradeStrength\":132}");

        var weakScore = scorer.score(weak);
        var strongScore = scorer.score(strong);

        assertThat(strongScore.score()).isGreaterThan(weakScore.score());
        assertThat(strongScore.recommendationReason()).contains("closingRecency", "vwapPosition", "dayHighProximity");
    }

    @Test
    void penalizesOverextensionAndWeakTradeStrength() {
        var balanced = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7,\"tradeStrength\":125}");
        var risky = detection("2026-09-04T06:15:00Z", "65", "55", "2.0", "8000000000", "-1.0",
                "{\"vwapDistanceRate\":8.0,\"dayHighDistanceRate\":5.0,\"tradeStrength\":75}");

        var balancedScore = scorer.score(balanced);
        var riskyScore = scorer.score(risky);

        assertThat(riskyScore.score()).isLessThan(balancedScore.score());
        assertThat(riskyScore.riskReason()).contains("vwapOverextension", "weakTradeStrength", "lateNegativeMomentum");
    }

    @Test
    void includesIntradayMovingAverageFeatureInReasons() {
        var detection = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7,\"tradeStrength\":125}");
        var ma = new IntradayMovingAverageFeature(true, 60, bd("105"), bd("104"), bd("102"), bd("100"),
                bd("0.961538"), bd("2.941176"), bd("5.000000"), true, true, false, false);

        var score = scorer.score(detection, ma);

        assertThat(score.recommendationReason()).contains("\"intradayMa\"", "\"bullishAlignment\":true",
                "\"goldenCross\":true");
        assertThat(score.riskReason()).contains("\"ma20Broken\":false");
    }

    @Test
    void includesDailyMovingAverageFeatureInReasons() {
        var detection = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7,\"tradeStrength\":125}");
        var dailyMa = new DailyMovingAverageFeature(true, 60, LocalDate.of(2026, 9, 3), bd("105"),
                bd("104"), bd("102"), bd("100"), bd("0.961538"), bd("2.941176"), bd("5.000000"),
                bd("0.500000"), true, true, true, true, false);

        var score = scorer.score(detection, IntradayMovingAverageFeature.empty(0), dailyMa);

        assertThat(score.recommendationReason()).contains("\"dailyMa\"", "\"closeAboveMa20\":true",
                "\"ma20Rising\":true");
        assertThat(score.riskReason()).contains("\"overextendedFromMa20\":false");
    }

    @Test
    void rewardsMovingAverageTrendAndPenalizesBreakdown() {
        var detection = detection("2026-09-04T06:15:00Z", "50", "20", "2.0", "5000000000", "1.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7,\"tradeStrength\":120}");
        var strongIntraday = new IntradayMovingAverageFeature(true, 60, bd("105"), bd("104"), bd("102"), bd("100"),
                bd("0.961538"), bd("2.941176"), bd("5.000000"), true, true, true, false);
        var strongDaily = new DailyMovingAverageFeature(true, 60, LocalDate.of(2026, 9, 3), bd("105"),
                bd("104"), bd("102"), bd("100"), bd("0.961538"), bd("2.941176"), bd("5.000000"),
                bd("0.500000"), true, true, true, true, false);
        var weakIntraday = new IntradayMovingAverageFeature(true, 60, bd("95"), bd("98"), bd("100"), bd("102"),
                bd("-3.061224"), bd("-5.000000"), bd("-6.862745"), false, false, false, true);
        var weakDaily = new DailyMovingAverageFeature(true, 60, LocalDate.of(2026, 9, 3), bd("88"),
                bd("91"), bd("95"), bd("100"), bd("-3.296703"), bd("-7.368421"), bd("-12.000000"),
                bd("-0.700000"), false, false, false, false, false);

        var strong = scorer.score(detection, strongIntraday, strongDaily);
        var weak = scorer.score(detection, weakIntraday, weakDaily);

        assertThat(strong.score()).isGreaterThan(weak.score());
        assertThat(strong.recommendationReason()).contains("intradayBullishAlignment", "intradayGoldenCross",
                "dailyTrendAlignment", "dailyMa20Rising");
        assertThat(weak.riskReason()).contains("intradayMa20Breakdown", "dailyTrendWeakness");
    }

    private ScannerDetection detection(String detectedAt, String opportunity, String risk, String volumeRatio,
            String dailyValue, String changeRate, String featureSnapshot) {
        ScannerDetection detection = mock(ScannerDetection.class);
        when(detection.getType()).thenReturn(ScannerType.VWAP_BREAKOUT);
        when(detection.getDetectedAt()).thenReturn(Instant.parse(detectedAt));
        when(detection.getOpportunityScore()).thenReturn(bd(opportunity));
        when(detection.getRiskScore()).thenReturn(bd(risk));
        when(detection.getVolumeRatio()).thenReturn(bd(volumeRatio));
        when(detection.getDailyValue()).thenReturn(bd(dailyValue));
        when(detection.getChangeRate()).thenReturn(bd(changeRate));
        when(detection.getFeatureSnapshot()).thenReturn(featureSnapshot);
        when(detection.getScore()).thenReturn(bd("5"));
        return detection;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
