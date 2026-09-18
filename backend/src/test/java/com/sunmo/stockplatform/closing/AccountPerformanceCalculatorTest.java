package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.closing.application.AccountPerformanceCalculator;
import com.sunmo.stockplatform.closing.application.AccountPerformanceCalculator.ClosedTrade;
import com.sunmo.stockplatform.closing.application.AccountPerformanceCalculator.DailyEquity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPerformanceCalculatorTest {
    private final AccountPerformanceCalculator calculator = new AccountPerformanceCalculator();
    private static final LocalDate START = LocalDate.of(2026, 9, 14);

    @Test
    void doesNotTurnMissingExecutionDataIntoZeroReturn() {
        var result = calculator.unavailable();
        assertThat(result.status()).isEqualTo("NOT_READY");
        assertThat(result.reason()).isEqualTo("PAPER_EXECUTION_LEDGER_MISSING");
        assertThat(result.cumulativeReturnRate()).isNull();
        assertThat(result.benchmarkStatus()).isEqualTo("NO_ALIGNED_INDEX_DATA");
    }

    @Test
    void includesNoTradeDayAndRemovesExternalCashFlowFromReturn() {
        List<LocalDate> dates = List.of(START, START.plusDays(1), START.plusDays(2));
        var result = calculator.calculate(dates, List.of(
                day(dates.get(0), "1000000", "0"),
                day(dates.get(1), "1100000", "0"),
                day(dates.get(2), "1200000", "100000")),
                List.of(new ClosedTrade(dates.get(1), bd("10000")),
                        new ClosedTrade(dates.get(2), bd("-5000"))));

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.observedTradingDays()).isEqualTo(3);
        assertThat(result.returnObservations()).isEqualTo(2);
        assertThat(result.cumulativeReturnRate()).isEqualByComparingTo("10.000000");
        assertThat(result.dailyPoints().getLast().returnRate()).isEqualByComparingTo("0.000000");
        assertThat(result.maxDrawdownRate()).isEqualByComparingTo("0.000000");
        assertThat(result.profitFactor()).isEqualByComparingTo("2.000000");
        assertThat(result.sharpeRatio()).isNull();
        assertThat(result.excessReturnRate()).isNull();
    }

    @Test
    void missingDayOrUnreconciledLedgerSuppressesAllPerformanceFigures() {
        List<LocalDate> dates = List.of(START, START.plusDays(1), START.plusDays(2));
        var missing = calculator.calculate(dates, List.of(day(START, "1000", "0"),
                day(START.plusDays(2), "1100", "0")), List.of());
        assertThat(missing.status()).isEqualTo("INCOMPLETE");
        assertThat(missing.reason()).isEqualTo("DAILY_EQUITY_MISSING");
        assertThat(missing.cumulativeReturnRate()).isNull();

        var unreconciled = calculator.calculate(dates.subList(0, 2), List.of(day(START, "1000", "0"),
                new DailyEquity(START.plusDays(1), bd("1100"), BigDecimal.ZERO, BigDecimal.ZERO,
                        true, false)), List.of());
        assertThat(unreconciled.reason()).isEqualTo("LEDGER_NOT_RECONCILED");
        assertThat(unreconciled.maxDrawdownRate()).isNull();
    }

    @Test
    void calculatesAccountDrawdownFromEquityPeakNotSingleStockLow() {
        List<LocalDate> dates = List.of(START, START.plusDays(1), START.plusDays(2));
        var result = calculator.calculate(dates, List.of(day(dates.get(0), "1000", "0"),
                day(dates.get(1), "1200", "0"), day(dates.get(2), "900", "0")), List.of());
        assertThat(result.cumulativeReturnRate()).isEqualByComparingTo("-10.000000");
        assertThat(result.maxDrawdownRate()).isEqualByComparingTo("25.000000");
    }

    @Test
    void computesRiskRatiosOnlyWithEnoughNonConstantDailyObservations() {
        List<LocalDate> dates = new ArrayList<>();
        List<DailyEquity> rows = new ArrayList<>();
        BigDecimal equity = bd("1000000");
        for (int index = 0; index <= 20; index++) {
            LocalDate date = START.plusDays(index);
            dates.add(date);
            if (index > 0) equity = equity.multiply(index % 2 == 0 ? bd("1.01") : bd("0.995"));
            rows.add(new DailyEquity(date, equity, BigDecimal.ZERO, BigDecimal.ZERO, true, true));
        }
        var result = calculator.calculate(dates, rows, List.of());
        assertThat(result.riskMetricStatus()).isEqualTo("READY");
        assertThat(result.sharpeRatio()).isNotNull();
        assertThat(result.sortinoRatio()).isNotNull();
        assertThat(result.profitFactor()).isNull();
    }

    private DailyEquity day(LocalDate date, String cash, String flow) {
        return new DailyEquity(date, bd(cash), BigDecimal.ZERO, bd(flow), true, true);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
