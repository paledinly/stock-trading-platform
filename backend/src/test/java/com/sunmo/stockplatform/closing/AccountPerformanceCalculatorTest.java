/*
 * AccountPerformanceCalculator(가상 계좌 성과 계산기)를 검사하는 테스트예요.
 * 일별 평가금액으로 누적 수익률, 최대 낙폭(MDD), Profit Factor, Sharpe/Sortino를 계산하고, 데이터가 빠지거나 장부가 맞지 않으면 숫자를 내지 않는지 확인해요.
 * 결측을 0% 수익으로 바꾸면 성과가 실제보다 좋아 보일 수 있어서 중요해요.
 */
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

/**
 * 회계 감사관처럼 계좌 성과 숫자가 완전한 장부에서만 나오는지 확인하는 테스트 모음이에요.
 */
class AccountPerformanceCalculatorTest {
    private final AccountPerformanceCalculator calculator = new AccountPerformanceCalculator();
    /** 테스트 기간의 첫 날짜예요. */
    private static final LocalDate START = LocalDate.of(2026, 9, 14);

    /**
     * 가상 체결 장부가 없을 때 0% 수익으로 바꾸지 않고 준비 안 됨으로 보고하는지 확인해요.
     * 입력: 없음(unavailable 호출). 기대: NOT_READY, 사유 PAPER_EXECUTION_LEDGER_MISSING, 누적 수익률 null, 벤치마크 NO_ALIGNED_INDEX_DATA예요.
     */
    @Test
    void doesNotTurnMissingExecutionDataIntoZeroReturn() {
        var result = calculator.unavailable();
        assertThat(result.status()).isEqualTo("NOT_READY");
        assertThat(result.reason()).isEqualTo("PAPER_EXECUTION_LEDGER_MISSING");
        assertThat(result.cumulativeReturnRate()).isNull();
        assertThat(result.benchmarkStatus()).isEqualTo("NO_ALIGNED_INDEX_DATA");
    }

    /**
     * 거래 없는 날도 관측일로 세고, 외부 입금(현금 흐름)은 수익률에서 빼는지 확인해요.
     * 준비: 평가금액 100만 → 110만 → 120만(이날 외부 입금 10만), 청산 손익 +1만과 -5천.
     * 기대: READY, 관측일 3, 수익률 관측 2, 누적 +10%, 마지막 날 수익률 0%(입금 제외), MDD 0%,
     * Profit Factor 2, 표본이 적어 Sharpe와 초과수익률은 null이에요.
     */
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

    /**
     * 하루라도 평가금액이 빠지거나 장부가 대사(reconcile, 맞춰 보기)되지 않으면 모든 성과 숫자를 숨기는지 확인해요.
     * 준비: 3일 중 가운데 날이 빠진 기록, 그리고 둘째 날이 대사되지 않은(마지막 인자 false) 기록.
     * 기대: 앞은 INCOMPLETE/DAILY_EQUITY_MISSING에 누적 수익률 null, 뒤는 LEDGER_NOT_RECONCILED에 MDD null이에요.
     */
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

    /**
     * 최대 낙폭을 개별 종목 저점이 아니라 계좌 평가금액의 고점 대비로 계산하는지 확인해요.
     * 입력: 평가금액 1000 → 1200 → 900. 기대: 누적 수익률 -10%, 최대 낙폭 25%(1200에서 900)예요.
     */
    @Test
    void calculatesAccountDrawdownFromEquityPeakNotSingleStockLow() {
        List<LocalDate> dates = List.of(START, START.plusDays(1), START.plusDays(2));
        var result = calculator.calculate(dates, List.of(day(dates.get(0), "1000", "0"),
                day(dates.get(1), "1200", "0"), day(dates.get(2), "900", "0")), List.of());
        assertThat(result.cumulativeReturnRate()).isEqualByComparingTo("-10.000000");
        assertThat(result.maxDrawdownRate()).isEqualByComparingTo("25.000000");
    }

    /**
     * 변동이 있는 일별 관측이 충분할 때만 Sharpe/Sortino 같은 위험 지표를 계산하는지 확인해요.
     * 준비: 21일 동안 +1%와 -0.5%가 번갈아 나오는 평가금액, 청산 거래는 없음.
     * 기대: 위험 지표 상태 READY, Sharpe와 Sortino는 값이 있고, 거래가 없어 Profit Factor는 null이에요.
     */
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

    /** 평가금액과 외부 현금 흐름만 정한, 완전하고 대사된 하루 기록을 만들어요. */
    private DailyEquity day(LocalDate date, String cash, String flow) {
        return new DailyEquity(date, bd(cash), BigDecimal.ZERO, bd(flow), true, true);
    }

    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
