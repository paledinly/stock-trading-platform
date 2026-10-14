/*
 * 가상 체결 성과를 계산할 때 빼야 하는 비용(매수·매도 수수료, 매도 세금, 슬리피지)을 closing.execution-cost 설정에서 읽어 와요.
 * 모든 값은 퍼센트 단위이고, 비어 있으면 0으로 봐요.
 * 다음 날 성과(OvernightPerformance) 계산과 전략 분석에서 비용 차감 수익률을 구할 때 사용해요.
 */
package com.sunmo.stockplatform.closing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 가상 매수·매도에 붙는 비용 비율을 모아 두는 설정이에요.
 * 장보기 영수증에서 물건값 말고 붙는 봉투값·배송비처럼, 수익률에서 빼야 할 부대 비용 목록이라고 보면 돼요.
 * (슬리피지: 원하는 가격보다 조금 불리하게 체결되는 차이를 말해요.)
 */
@ConfigurationProperties(prefix = "closing.execution-cost")
public record TradingCostProperties(
        BigDecimal buyFeePercent,
        BigDecimal sellFeePercent,
        BigDecimal sellTaxPercent,
        BigDecimal buySlippagePercent,
        BigDecimal sellSlippagePercent) {

    /**
     * 하는 일: 빈 값을 0으로 채우고 음수나 100% 이상 매도 비용 같은 잘못된 값을 막아요.
     * 받는 것: 설정 파일의 비용 비율(퍼센트)이에요.
     * 돌려주는 것: 검증된 비용 설정 객체예요.
     */
    public TradingCostProperties {
        buyFeePercent = value(buyFeePercent);
        sellFeePercent = value(sellFeePercent);
        sellTaxPercent = value(sellTaxPercent);
        buySlippagePercent = value(buySlippagePercent);
        sellSlippagePercent = value(sellSlippagePercent);
        if (buyFeePercent.signum() < 0 || sellFeePercent.signum() < 0 || sellTaxPercent.signum() < 0
                || buySlippagePercent.signum() < 0 || sellSlippagePercent.signum() < 0)
            throw new IllegalArgumentException("Execution costs must not be negative");
        // 매도 비용 합이 100% 이상이면 매도 대금이 0 이하가 되어 계산이 깨지므로 막아요.
        if (sellFeePercent.add(sellTaxPercent).add(sellSlippagePercent).compareTo(BigDecimal.valueOf(100)) >= 0)
            throw new IllegalArgumentException("Total sell costs must be below 100 percent");
    }

    /**
     * 하는 일: 비용이 하나라도 설정돼 있는지 확인해요.
     * 받는 것: 없어요.
     * 돌려주는 것: 0보다 큰 비용이 하나라도 있으면 true예요.
     */
    public boolean applied() {
        return buyFeePercent.signum() > 0 || sellFeePercent.signum() > 0 || sellTaxPercent.signum() > 0
                || buySlippagePercent.signum() > 0 || sellSlippagePercent.signum() > 0;
    }

    /**
     * 하는 일: null 값을 0으로 바꿔요.
     * 받는 것: 비용 비율(없을 수 있음)이에요.
     * 돌려주는 것: 원래 값 또는 0이에요.
     */
    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
