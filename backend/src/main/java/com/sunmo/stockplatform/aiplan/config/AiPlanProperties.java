/*
 * AI 장전 계획 자동 채점 설정이에요. AiPlanOutcomeService가 매일 채점할지 정할 때 써요.
 */
package com.sunmo.stockplatform.aiplan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** evaluationEnabled: 매일 자동 채점 여부(기본 켜짐). 채점 시각은 ai-plan.evaluation-cron(기본 21:30)이에요. */
@ConfigurationProperties(prefix = "ai-plan")
public record AiPlanProperties(Boolean evaluationEnabled) {
    /** 하는 일: 값이 없으면 자동 채점을 켜요. 채점은 DB 읽기·쓰기만 하고 KIS를 부르지 않아요. */
    public AiPlanProperties {
        evaluationEnabled = evaluationEnabled == null || evaluationEnabled;
    }
}
