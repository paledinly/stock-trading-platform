package com.sunmo.stockplatform.closing.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AccountPerformanceCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final int MIN_RISK_OBSERVATIONS = 20;

    public Report unavailable() {
        return new Report("NOT_READY", "PAPER_EXECUTION_LEDGER_MISSING", 0, 0,
                null, null, null, null, null, null, null, "INSUFFICIENT_SAMPLE",
                "NO_ALIGNED_INDEX_DATA", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TRADING_DAYS_PER_YEAR, List.of());
    }

    public Report calculate(List<LocalDate> tradingDates, List<DailyEquity> snapshots,
            List<ClosedTrade> closedTrades) {
        if (tradingDates.isEmpty() || snapshots.isEmpty()) return unavailable();
        if (closedTrades.stream().anyMatch(trade -> trade.closedOn() == null
                || !tradingDates.contains(trade.closedOn())))
            throw new IllegalArgumentException("Closed trade outside account reporting dates");
        Map<LocalDate, DailyEquity> byDate = new HashMap<>();
        for (DailyEquity snapshot : snapshots) {
            if (byDate.putIfAbsent(snapshot.date(), snapshot) != null)
                throw new IllegalArgumentException("Duplicate account snapshot date: " + snapshot.date());
        }
        BigDecimal firstEquity = null;
        BigDecimal previousEquity = null;
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        List<BigDecimal> dailyReturns = new ArrayList<>();
        List<DailyPoint> points = new ArrayList<>();
        LocalDate previousDate = null;
        for (LocalDate date : tradingDates) {
            if (previousDate != null && !date.isAfter(previousDate))
                throw new IllegalArgumentException("Trading dates must be strictly increasing");
            previousDate = date;
            DailyEquity row = byDate.get(date);
            if (row == null || !row.complete()) return incomplete("DAILY_EQUITY_MISSING", points, firstEquity);
            if (!row.ledgerReconciled()) return incomplete("LEDGER_NOT_RECONCILED", points, firstEquity);
            if (row.cash() == null || row.positionValue() == null || row.externalFlowAtClose() == null
                    || row.cash().signum() < 0 || row.positionValue().signum() < 0)
                return incomplete("INVALID_ACCOUNT_BALANCE", points, firstEquity);
            BigDecimal equity = row.cash().add(row.positionValue());
            if (equity.signum() <= 0) return incomplete("INVALID_ACCOUNT_BALANCE", points, firstEquity);
            if (equity.subtract(row.externalFlowAtClose()).signum() < 0)
                return incomplete("INVALID_ACCOUNT_BALANCE", points, firstEquity);
            if (firstEquity == null) {
                firstEquity = equity;
                if (row.externalFlowAtClose().signum() != 0)
                    return incomplete("BASELINE_EXTERNAL_FLOW", points, firstEquity);
                points.add(new DailyPoint(date, equity, null, BigDecimal.ZERO));
            } else {
                BigDecimal dailyReturn = equity.subtract(row.externalFlowAtClose())
                        .divide(previousEquity, 12, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
                growth = growth.multiply(BigDecimal.ONE.add(dailyReturn));
                peak = peak.max(growth);
                if (peak.signum() > 0) maxDrawdown = maxDrawdown.max(peak.subtract(growth)
                        .divide(peak, 12, RoundingMode.HALF_UP));
                dailyReturns.add(dailyReturn);
                points.add(new DailyPoint(date, equity, percent(dailyReturn), percent(growth.subtract(BigDecimal.ONE))));
            }
            previousEquity = equity;
        }
        BigDecimal[] risk = riskRatios(dailyReturns);
        return new Report("READY", null, points.size(), dailyReturns.size(), firstEquity, previousEquity,
                percent(growth.subtract(BigDecimal.ONE)), percent(maxDrawdown), profitFactor(closedTrades),
                risk[0], risk[1], dailyReturns.size() < MIN_RISK_OBSERVATIONS ? "INSUFFICIENT_SAMPLE"
                        : risk[0] == null || risk[1] == null ? "ZERO_DENOMINATOR" : "READY",
                "NO_ALIGNED_INDEX_DATA", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TRADING_DAYS_PER_YEAR, List.copyOf(points));
    }

    private Report incomplete(String reason, List<DailyPoint> points, BigDecimal firstEquity) {
        return new Report("INCOMPLETE", reason, points.size(), Math.max(points.size() - 1, 0),
                firstEquity, null, null, null, null, null, null, "NOT_CALCULATED",
                "NO_ALIGNED_INDEX_DATA", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TRADING_DAYS_PER_YEAR, List.copyOf(points));
    }

    private BigDecimal profitFactor(List<ClosedTrade> trades) {
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (ClosedTrade trade : trades) {
            if (trade.netPnl() == null) throw new IllegalArgumentException("Missing net trade PnL");
            if (trade.netPnl().signum() > 0) gains = gains.add(trade.netPnl());
            if (trade.netPnl().signum() < 0) losses = losses.subtract(trade.netPnl());
        }
        return losses.signum() == 0 ? null : gains.divide(losses, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal[] riskRatios(List<BigDecimal> dailyReturns) {
        BigDecimal[] result = new BigDecimal[2];
        if (dailyReturns.size() < MIN_RISK_OBSERVATIONS) return result;
        double[] values = dailyReturns.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        double variance = java.util.Arrays.stream(values).map(value -> Math.pow(value - mean, 2)).sum()
                / (values.length - 1);
        double downside = java.util.Arrays.stream(values).map(value -> Math.pow(Math.min(value, 0), 2)).sum()
                / values.length;
        if (variance > 0) result[0] = ratio(mean / Math.sqrt(variance) * Math.sqrt(TRADING_DAYS_PER_YEAR));
        if (downside > 0) result[1] = ratio(mean / Math.sqrt(downside) * Math.sqrt(TRADING_DAYS_PER_YEAR));
        return result;
    }

    private BigDecimal percent(BigDecimal value) {
        return value.multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP) : null;
    }

    public record DailyEquity(LocalDate date, BigDecimal cash, BigDecimal positionValue,
            BigDecimal externalFlowAtClose, boolean complete, boolean ledgerReconciled) { }

    public record ClosedTrade(LocalDate closedOn, BigDecimal netPnl) { }

    public record DailyPoint(LocalDate date, BigDecimal equity, BigDecimal returnRate,
            BigDecimal cumulativeReturnRate) { }

    public record Report(String status, String reason, int observedTradingDays, int returnObservations,
            BigDecimal initialEquity, BigDecimal endingEquity, BigDecimal cumulativeReturnRate,
            BigDecimal maxDrawdownRate, BigDecimal profitFactor, BigDecimal sharpeRatio, BigDecimal sortinoRatio,
            String riskMetricStatus, String benchmarkStatus, BigDecimal excessReturnRate,
            BigDecimal annualRiskFreeRate, BigDecimal annualMinimumReturnRate, int annualTradingDays,
            List<DailyPoint> dailyPoints) { }
}
