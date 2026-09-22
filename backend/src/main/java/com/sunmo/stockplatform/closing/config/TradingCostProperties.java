package com.sunmo.stockplatform.closing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "closing.execution-cost")
public record TradingCostProperties(
        BigDecimal buyFeePercent,
        BigDecimal sellFeePercent,
        BigDecimal sellTaxPercent,
        BigDecimal buySlippagePercent,
        BigDecimal sellSlippagePercent) {

    public TradingCostProperties {
        buyFeePercent = value(buyFeePercent);
        sellFeePercent = value(sellFeePercent);
        sellTaxPercent = value(sellTaxPercent);
        buySlippagePercent = value(buySlippagePercent);
        sellSlippagePercent = value(sellSlippagePercent);
        if (buyFeePercent.signum() < 0 || sellFeePercent.signum() < 0 || sellTaxPercent.signum() < 0
                || buySlippagePercent.signum() < 0 || sellSlippagePercent.signum() < 0)
            throw new IllegalArgumentException("Execution costs must not be negative");
        if (sellFeePercent.add(sellTaxPercent).add(sellSlippagePercent).compareTo(BigDecimal.valueOf(100)) >= 0)
            throw new IllegalArgumentException("Total sell costs must be below 100 percent");
    }

    public boolean applied() {
        return buyFeePercent.signum() > 0 || sellFeePercent.signum() > 0 || sellTaxPercent.signum() > 0
                || buySlippagePercent.signum() > 0 || sellSlippagePercent.signum() > 0;
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
