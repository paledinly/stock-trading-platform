/*
 * OpportunityRiskScorer가 기회 점수와 위험 점수를 따로 계산하는지 확인해요.
 * 이 점수는 상승 확률이 아니라 후보를 정렬·설명하기 위한 값이며, 근거 항목이 함께 남아야 해요.
 */
package com.sunmo.stockplatform.scanner;

import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.application.ScannerEvaluator;
import com.sunmo.stockplatform.scanner.score.OpportunityRiskScorer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 기회/위험 점수 계산을 점검하는 테스트예요. */
class OpportunityRiskScorerTest {
    private final OpportunityRiskScorer scorer = new OpportunityRiskScorer();

    /**
     * 강한 신호에서 기회 점수는 높고 위험 점수는 낮게 나오는지 확인해요.
     * 준비: 등락률 2.5·거래량 배수 3 지표와 VWAP(거래량 가중 평균가) 거리 2%, 체결강도 145인 특성값을 넣어요.
     * 기대: 기회 60 초과, 위험 10 미만이고 각 근거 항목과 점수 버전이 기록돼요.
     */
    @Test
    void scoresOpportunityAndRiskSeparately() {
        var metrics = new ScannerEvaluator.Metrics(true, bd("2.5"), bd("3.0"), bd("5.5"), "READY",
                bd("100"), bd("95"));
        var score = scorer.score(metrics, feature(bd("2.0"), bd("145"), bd("0.2"), 30, 10));

        assertThat(score.opportunityScore()).isGreaterThan(bd("60"));
        assertThat(score.riskScore()).isLessThan(bd("10"));
        assertThat(score.opportunityFactors()).containsKeys("priceMomentum", "volumeExpansion", "vwapLeadership");
        assertThat(score.riskFactors()).containsKeys("vwapOverextension", "lastTickSellImbalance");
        assertThat(score.scoreVersion()).isEqualTo("opportunity-risk-v1");
    }

    /**
     * 약하고 과열된 신호에서는 위험 점수가 높아지는지 확인해요.
     * 준비: 음수 등락률, VWAP 거리 5.5%, 체결강도 72, 매도 우위 특성값을 넣어요.
     * 기대: 위험 45 초과, 기회 25 미만이에요.
     */
    @Test
    void raisesRiskForWeakAndOverextendedSignals() {
        var metrics = new ScannerEvaluator.Metrics(true, bd("-1.2"), bd("1.1"), bd("-0.1"), "READY",
                bd("100"), bd("95"));
        var score = scorer.score(metrics, feature(bd("5.5"), bd("72"), bd("4.0"), 5, 25));

        assertThat(score.riskScore()).isGreaterThan(bd("45"));
        assertThat(score.opportunityScore()).isLessThan(bd("25"));
    }

    /** VWAP 거리·체결강도·고가 거리·매수/매도 증가량만 바꾼 시장 특성 스냅샷을 만드는 도우미예요. */
    private MarketFeatureSnapshot feature(BigDecimal vwapDistance, BigDecimal strength, BigDecimal highDistance,
                                          long buyDelta, long sellDelta) {
        return new MarketFeatureSnapshot("005930", LocalDate.parse("2026-09-03"), Instant.parse("2026-09-03T01:40:00Z"),
                bd("105"), 1000, bd("105000"), bd("100"), bd("106"), bd("99"), bd("103"),
                vwapDistance, bd("0.1"), bd("3.0"), bd("2.5"), strength, buyDelta, sellDelta,
                bd("0.55"), highDistance, false, bd("100"), MarketFeatureSnapshot.VERSION);
    }

    /** 문자열을 BigDecimal로 바꾸는 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
