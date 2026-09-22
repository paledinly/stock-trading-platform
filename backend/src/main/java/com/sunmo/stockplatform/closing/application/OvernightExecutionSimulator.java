package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Component
public class OvernightExecutionSimulator {
    public static final String VERSION = "overnight-execution-v1";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final TradingCostProperties costs;

    public OvernightExecutionSimulator(TradingCostProperties costs) {
        this.costs = costs;
    }

    public ExecutionResult targetOrStop(BigDecimal entryReference, List<StockCandle> candles,
            BigDecimal targetRate, BigDecimal stopRate) {
        if (entryReference == null || entryReference.signum() <= 0 || candles.isEmpty()) return null;
        BigDecimal target = threshold(entryReference, targetRate);
        BigDecimal stop = threshold(entryReference, stopRate);
        for (StockCandle candle : candles) {
            if (candle.getOpen().compareTo(stop) <= 0)
                return exit(entryReference, candle.getStartTime(), candle.getOpen(), "GAP_STOP", false, true, false);
            if (candle.getOpen().compareTo(target) >= 0)
                return exit(entryReference, candle.getStartTime(), candle.getOpen(), "GAP_TARGET", true, false, false);
            boolean targetHit = candle.getHigh().compareTo(target) >= 0;
            boolean stopHit = candle.getLow().compareTo(stop) <= 0;
            if (targetHit && stopHit)
                return exit(entryReference, candle.getStartTime(), stop, "AMBIGUOUS_STOP", true, true, true);
            if (stopHit)
                return exit(entryReference, candle.getStartTime(), stop, "STOP", false, true, false);
            if (targetHit)
                return exit(entryReference, candle.getStartTime(), target, "TARGET", true, false, false);
        }
        StockCandle last = candles.getLast();
        return exit(entryReference, last.getStartTime(), last.getClose(), "TIME_EXIT", false, false, false);
    }

    public ExecutionResult exit(BigDecimal entryReference, Instant exitAt, BigDecimal exitReference,
            String reason, boolean targetHit, boolean stopHit, boolean ambiguous) {
        if (entryReference == null || entryReference.signum() <= 0 || exitReference == null) return null;
        BigDecimal gross = rate(exitReference, entryReference);
        BigDecimal buyCash = entryReference.multiply(BigDecimal.ONE.add(percent(
                costs.buySlippagePercent().add(costs.buyFeePercent()))));
        BigDecimal sellCash = exitReference.multiply(BigDecimal.ONE.subtract(percent(
                costs.sellSlippagePercent().add(costs.sellFeePercent()).add(costs.sellTaxPercent()))));
        return new ExecutionResult(entryReference, exitReference, exitAt, gross, rate(sellCash, buyCash),
                targetHit, stopHit, ambiguous, reason, costs.applied(), VERSION);
    }

    private BigDecimal threshold(BigDecimal base, BigDecimal rate) {
        return base.multiply(BigDecimal.ONE.add(percent(rate)));
    }

    private BigDecimal percent(BigDecimal value) {
        return value.divide(HUNDRED, 10, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal price, BigDecimal base) {
        return price.subtract(base).divide(base, 10, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    public record ExecutionResult(
            BigDecimal entryReferencePrice,
            BigDecimal exitReferencePrice,
            Instant exitAt,
            BigDecimal grossReturnRate,
            BigDecimal netReturnRate,
            boolean targetHit,
            boolean stopHit,
            boolean ambiguous,
            String exitReason,
            boolean costsApplied,
            String modelVersion) {
    }
}
