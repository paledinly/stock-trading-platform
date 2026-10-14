/*
 * 감지 한 건의 기회 점수와 위험 점수를 담는 결과 record 파일이에요.
 * 총점과 항목별 세부 점수, 점수 버전을 함께 보관해요.
 * OpportunityRiskScorer가 만들고 ScannerEngine이 감지 기록에 붙여요.
 */
package com.sunmo.stockplatform.scanner.score;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 기회 점수와 위험 점수(각 0~100), 항목별 세부 점수를 담는 결과 상자예요.
 * 건강검진 결과표처럼 총점과 항목별 수치를 함께 적어 둬요. 점수는 상승 확률이 아니에요.
 */
public record OpportunityRiskScore(
        BigDecimal opportunityScore,
        BigDecimal riskScore,
        Map<String, BigDecimal> opportunityFactors,
        Map<String, BigDecimal> riskFactors,
        String scoreVersion) {
    // 점수 계산 방식이 바뀌면 이 버전 문자열을 바꿔 이전 기록과 구분해요.
    public static final String VERSION = "opportunity-risk-v1";
}
