/*
 * 연구용 투자자(외국인·기관·개인) 일별 순매수 수집 설정이에요. application.yml의 market.investor-flow 아래 값과 연결돼요.
 */
package com.sunmo.stockplatform.candle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * enabled: 매일 자동 수집 여부. floor: 과거로 거슬러 받을 가장 오래된 날짜(기본 2018-01-01, 수정주가 일봉과 맞춰요).
 */
@ConfigurationProperties(prefix = "market.investor-flow")
public record InvestorFlowHistoryProperties(boolean enabled, LocalDate floor) {
    public InvestorFlowHistoryProperties {
        floor = floor == null ? LocalDate.of(2018, 1, 1) : floor;
    }
}
