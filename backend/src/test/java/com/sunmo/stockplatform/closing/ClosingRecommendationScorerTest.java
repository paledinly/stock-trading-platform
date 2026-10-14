/*
 * ClosingRecommendationScorer(마감 추천 점수 계산기)를 확인하는 테스트예요.
 * 마감 강도·유동성 가점, 과열·약한 체결강도 감점, 분봉/일봉 이동평균 반영, 결측 Feature 처리가 의도대로 되는지 봐요.
 * 점수는 상승 확률이 아니라 상대 순위용이라서, 여기서는 절댓값보다 대소 관계와 사유 기록을 주로 확인해요.
 */
package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer;
import com.sunmo.stockplatform.closing.application.DailyMovingAverageFeature;
import com.sunmo.stockplatform.closing.application.IntradayMovingAverageFeature;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 심사위원처럼 탐지 후보마다 가점과 감점을 매기고 그 사유를 남기는지 확인해요.
 */
class ClosingRecommendationScorerTest {
    private final ClosingRecommendationScorer scorer =
            new ClosingRecommendationScorer(new ObjectMapper().findAndRegisterModules());

    /**
     * 전략 버전 문자열이 DB 컬럼 길이(40자)를 넘지 않는지 확인해요.
     * 입력: ClosingRecommendation.STRATEGY_VERSION 상수.
     * 기대: 길이 40 이하예요.
     */
    @Test
    void strategyVersionFitsDatabaseColumn() {
        assertThat(ClosingRecommendation.STRATEGY_VERSION).hasSizeLessThanOrEqualTo(40);
    }

    /**
     * 마감에 가깝고 강하며 유동성이 큰 탐지에 더 높은 점수를 주는지 확인해요.
     * 입력: 14:31(KST) 약한 탐지와 15:20(KST) 강한 탐지(높은 기회점수·거래대금·VWAP 위·고가 근접·체결강도).
     * 기대: 강한 쪽 점수가 더 높고, 사유에 closingRecency·vwapPosition·dayHighProximity가 있어요.
     * 15:20 탐지는 점수 계산만 보는 입력이라 15:15 입력 마감 규칙과는 별개로 보여요.
     */
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

    /**
     * VWAP 대비 과열, 약한 체결강도, 마감 무렵 하락 모멘텀을 감점하는지 확인해요.
     * 입력: 같은 시각·기회점수의 균형 탐지와 위험 탐지(위험점수 55, VWAP +8%, 체결강도 75, 등락 -1%).
     * 기대: 위험 쪽 점수가 더 낮고 위험 사유에 세 감점 항목이 있어요.
     */
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

    /**
     * 분봉 이동평균 Feature가 추천·위험 사유 JSON에 기록되는지 확인해요.
     * 입력: 정배열·골든크로스이고 MA20 이탈이 없는 분봉 이동평균 Feature.
     * 기대: 추천 사유에 intradayMa·bullishAlignment·goldenCross, 위험 사유에 ma20Broken=false가 있어요.
     */
    @Test
    void includesIntradayMovingAverageFeatureInReasons() {
        var detection = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7,\"tradeStrength\":125}");
        var ma = new IntradayMovingAverageFeature(true, 60, bd("105"), bd("104"), bd("102"), bd("100"),
                bd("0.961538"), bd("2.941176"), bd("5.000000"), true, true, false, false);

        var score = scorer.score(detection, ma, DailyMovingAverageFeature.empty(0));

        assertThat(score.recommendationReason()).contains("\"intradayMa\"", "\"bullishAlignment\":true",
                "\"goldenCross\":true");
        assertThat(score.riskReason()).contains("\"ma20Broken\":false");
    }

    /**
     * 일봉 이동평균 Feature가 추천·위험 사유 JSON에 기록되는지 확인해요.
     * 입력: 전일(09-03) 기준 종가가 MA20 위이고 MA20이 상승 중인 일봉 Feature.
     * 기대: 추천 사유에 dailyMa·closeAboveMa20·ma20Rising, 위험 사유에 overextendedFromMa20=false가 있어요.
     */
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

    /**
     * 이동평균 추세가 좋으면 가점, 무너지면 감점하는지 확인해요.
     * 입력: 같은 탐지에 강한(정배열·상승) 분봉/일봉 Feature와 약한(역배열·MA20 이탈) Feature를 각각 붙여요.
     * 기대: 강한 쪽 점수가 더 높고, 각각 추세 가점 사유와 이탈·약세 위험 사유가 기록돼요.
     */
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

    /**
     * 빠진 Feature를 0으로 간주하지 않고 평가하지 않은 값(null)으로 남기는지 확인해요.
     * 입력: Feature 스냅샷이 빈 JSON("{}")인 탐지.
     * 기대: VWAP·고가 거리 값은 null, 관련 가점·감점은 0.000이에요.
     */
    @Test
    void missingFeatureIsUnassessedInsteadOfBeingTreatedAsZero() {
        var missing = detection("2026-09-04T06:15:00Z", "50", "20", "2.0", "5000000000", "1.0", "{}");

        var result = scorer.score(missing);

        assertThat(result.recommendationReason()).contains("\"vwapDistanceRate\":null",
                "\"dayHighDistanceRate\":null", "\"dayHighProximity\":\"0.000\"");
        assertThat(result.riskReason()).contains("\"weakTradeStrength\":\"0.000\"");
    }

    /**
     * 숫자가 아닌 Feature 값이 있어도 예외 없이, 그 필드가 없을 때와 같은 점수를 내는지 확인해요.
     * 입력: tradeStrength가 "abc"인 탐지와 tradeStrength 필드가 아예 없는 탐지(나머지는 같아요).
     * 기대: 예외가 없고 두 점수·근거가 같으며 tradeStrength는 null로 기록돼요.
     */
    @Test
    void malformedFeatureValueIsTreatedAsMissing() {
        var malformed = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7,\"tradeStrength\":\"abc\"}");
        var absent = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7}");

        var malformedScore = scorer.score(malformed);
        var absentScore = scorer.score(absent);

        assertThat(malformedScore.score()).isEqualByComparingTo(absentScore.score());
        assertThat(malformedScore.recommendationReason()).isEqualTo(absentScore.recommendationReason());
        assertThat(malformedScore.riskReason()).contains("\"tradeStrength\":null", "\"weakTradeStrength\":\"0.000\"");
    }

    /**
     * 정상 숫자 값은 문자열 숫자와 JSON 숫자 모두 이전과 똑같은 점수를 내는지 확인해요.
     * 입력: 같은 값을 JSON 숫자로 담은 탐지와 숫자 문자열로 담은 탐지.
     * 기대: 두 점수가 같고, 이전 기준값(체결강도 감점 등)이 그대로예요.
     */
    @Test
    void normalNumericValuesKeepIdenticalScores() {
        var numeric = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":1.5,\"dayHighDistanceRate\":0.7,\"tradeStrength\":80}");
        var text = detection("2026-09-04T06:15:00Z", "65", "10", "2.0", "8000000000", "2.0",
                "{\"vwapDistanceRate\":\"1.5\",\"dayHighDistanceRate\":\"0.7\",\"tradeStrength\":\"80\"}");

        var numericScore = scorer.score(numeric);
        var textScore = scorer.score(text);

        assertThat(textScore.score()).isEqualByComparingTo(numericScore.score());
        assertThat(numericScore.riskReason()).contains("\"weakTradeStrength\":\"3.000\"");
    }

    /** 주어진 시각·점수·거래 지표·Feature JSON을 가진 VWAP 돌파 탐지 목(mock, 가짜 객체)을 만들어요. */
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

    /** 문자열을 BigDecimal로 바꾸는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
