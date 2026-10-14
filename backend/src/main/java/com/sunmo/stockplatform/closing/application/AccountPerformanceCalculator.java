/*
 * 가상 계좌의 하루 단위 자산 기록으로 누적 수익률, 최대 낙폭(MDD), 손익비, 샤프/소르티노 비율을 계산해요.
 * 기록이 빠지거나 장부가 맞지 않으면 숫자를 지어내지 않고 INCOMPLETE와 이유를 돌려줘요.
 * ClosingRecommendationController가 쓰며, 지금 API는 가상 체결 장부가 없어 unavailable()만 돌려줘요.
 */
package com.sunmo.stockplatform.closing.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 날짜별 계좌 잔고를 순서대로 읽어 계좌 성과 보고서를 만드는 계산기예요.
 * 가계부를 하루씩 넘기며 "어제보다 얼마나 늘었나, 최고점에서 얼마나 떨어졌나"를 적다가,
 * 빈 날이나 안 맞는 장부를 만나면 계산을 멈추고 "여기서 막혔어요"라고 알려 주는 회계 담당자라고 생각하면 돼요.
 */
@Component
public class AccountPerformanceCalculator {
    // 퍼센트 변환 배수, 연환산에 쓰는 연간 거래일 수, 위험 비율 계산에 필요한 최소 일간 수익률 개수예요.
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final int MIN_RISK_OBSERVATIONS = 20;

    /**
     * 가상 체결 장부가 없어 계산할 수 없을 때 쓰는 "아직 준비 안 됨" 보고서를 만들어요.
     * 받는 것: 없음.
     * 돌려주는 것: status=NOT_READY, reason=PAPER_EXECUTION_LEDGER_MISSING인 빈 Report.
     */
    public Report unavailable() {
        return new Report("NOT_READY", "PAPER_EXECUTION_LEDGER_MISSING", 0, 0,
                null, null, null, null, null, null, null, "INSUFFICIENT_SAMPLE",
                "NO_ALIGNED_INDEX_DATA", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TRADING_DAYS_PER_YEAR, List.of());
    }

    /**
     * 거래일 목록과 날짜별 잔고, 청산된 거래로 계좌 성과를 계산해요.
     * 받는 것: 오름차순 거래일 목록, 날짜별 자산 스냅샷, 청산 거래(청산일과 순손익) 목록.
     * 돌려주는 것: 끝까지 계산되면 READY 보고서, 중간에 결측·불일치가 있으면 INCOMPLETE 보고서.
     * 입력이 모순(중복 날짜, 순서 뒤집힘, 보고 기간 밖 청산)이면 IllegalArgumentException을 던져요.
     */
    public Report calculate(List<LocalDate> tradingDates, List<DailyEquity> snapshots,
            List<ClosedTrade> closedTrades) {
        // 준비: 입력이 비었으면 계산하지 않고, 청산 거래가 보고 기간 밖이면 오류로 막아요.
        if (tradingDates.isEmpty() || snapshots.isEmpty()) return unavailable();
        if (closedTrades.stream().anyMatch(trade -> trade.closedOn() == null
                || !tradingDates.contains(trade.closedOn())))
            throw new IllegalArgumentException("Closed trade outside account reporting dates");
        Map<LocalDate, DailyEquity> byDate = new HashMap<>();
        for (DailyEquity snapshot : snapshots) {
            if (byDate.putIfAbsent(snapshot.date(), snapshot) != null)
                throw new IllegalArgumentException("Duplicate account snapshot date: " + snapshot.date());
        }
        // 누적 성장률(growth)은 1에서 시작하고, 최고점(peak) 대비 하락폭 중 가장 큰 값을 MDD로 기억해요.
        BigDecimal firstEquity = null;
        BigDecimal previousEquity = null;
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        List<BigDecimal> dailyReturns = new ArrayList<>();
        List<DailyPoint> points = new ArrayList<>();
        LocalDate previousDate = null;
        // 처리: 거래일을 하나씩 보며 그날 잔고가 있고 완전하며 장부와 맞는지 먼저 확인해요.
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
            // 첫날은 기준점이에요. 기준일에 외부 입출금이 있으면 수익률 기준이 흔들리므로 INCOMPLETE로 멈춰요.
            if (firstEquity == null) {
                firstEquity = equity;
                if (row.externalFlowAtClose().signum() != 0)
                    return incomplete("BASELINE_EXTERNAL_FLOW", points, firstEquity);
                points.add(new DailyPoint(date, equity, null, BigDecimal.ZERO));
            } else {
                // 일간 수익률 = (오늘 자산 - 장 마감 시 외부 입출금) / 어제 자산 - 1. 입출금을 빼서 순수 운용 성과만 봐요.
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
        // 결과: 위험 비율은 표본이 20개 미만이면 INSUFFICIENT_SAMPLE, 분모가 0이면 ZERO_DENOMINATOR로 표시해요.
        // 지수(벤치마크) 비교는 아직 연결되지 않아 항상 NO_ALIGNED_INDEX_DATA예요.
        BigDecimal[] risk = riskRatios(dailyReturns);
        return new Report("READY", null, points.size(), dailyReturns.size(), firstEquity, previousEquity,
                percent(growth.subtract(BigDecimal.ONE)), percent(maxDrawdown), profitFactor(closedTrades),
                risk[0], risk[1], dailyReturns.size() < MIN_RISK_OBSERVATIONS ? "INSUFFICIENT_SAMPLE"
                        : risk[0] == null || risk[1] == null ? "ZERO_DENOMINATOR" : "READY",
                "NO_ALIGNED_INDEX_DATA", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TRADING_DAYS_PER_YEAR, List.copyOf(points));
    }

    /**
     * 계산을 중간에 멈출 때 지금까지의 일별 포인트를 담은 INCOMPLETE 보고서를 만들어요.
     * 받는 것: 멈춘 이유 코드, 지금까지 쌓은 일별 포인트, 기준일 자산(없으면 null).
     * 돌려주는 것: 수익률·위험 지표를 비운 INCOMPLETE Report.
     */
    private Report incomplete(String reason, List<DailyPoint> points, BigDecimal firstEquity) {
        return new Report("INCOMPLETE", reason, points.size(), Math.max(points.size() - 1, 0),
                firstEquity, null, null, null, null, null, null, "NOT_CALCULATED",
                "NO_ALIGNED_INDEX_DATA", null, BigDecimal.ZERO, BigDecimal.ZERO,
                TRADING_DAYS_PER_YEAR, List.copyOf(points));
    }

    /**
     * 손익비(Profit Factor) = 이익 거래 합계 / 손실 거래 합계(절댓값)를 계산해요.
     * 받는 것: 청산 거래 목록. 순손익이 비어 있으면 오류를 던져요.
     * 돌려주는 것: 손익비, 손실 거래가 하나도 없으면 null.
     */
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

    /**
     * 일간 수익률로 연환산 샤프 비율(전체 변동성 기준)과 소르티노 비율(하락 변동성 기준)을 계산해요.
     * 받는 것: 일간 수익률 목록(소수 단위).
     * 돌려주는 것: [샤프, 소르티노] 배열. 표본이 20개 미만이거나 분모가 0이면 해당 칸은 null이에요.
     */
    private BigDecimal[] riskRatios(List<BigDecimal> dailyReturns) {
        BigDecimal[] result = new BigDecimal[2];
        if (dailyReturns.size() < MIN_RISK_OBSERVATIONS) return result;
        double[] values = dailyReturns.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        // 무위험 수익률과 최소 요구 수익률은 0으로 보고 계산해요(보고서의 annual*Rate가 0인 이유예요).
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        double variance = java.util.Arrays.stream(values).map(value -> Math.pow(value - mean, 2)).sum()
                / (values.length - 1);
        double downside = java.util.Arrays.stream(values).map(value -> Math.pow(Math.min(value, 0), 2)).sum()
                / values.length;
        if (variance > 0) result[0] = ratio(mean / Math.sqrt(variance) * Math.sqrt(TRADING_DAYS_PER_YEAR));
        if (downside > 0) result[1] = ratio(mean / Math.sqrt(downside) * Math.sqrt(TRADING_DAYS_PER_YEAR));
        return result;
    }

    /**
     * 소수 비율을 퍼센트로 바꿔요(0.0123 → 1.23).
     * 받는 것: 소수 비율. 돌려주는 것: 소수점 6자리 퍼센트 값.
     */
    private BigDecimal percent(BigDecimal value) {
        return value.multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * double 비율 값을 소수점 6자리 BigDecimal로 바꿔요.
     * 받는 것: 계산된 비율. 돌려주는 것: 유한한 값이면 BigDecimal, 무한대·NaN이면 null.
     */
    private BigDecimal ratio(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP) : null;
    }

    /** 하루치 계좌 잔고 기록이에요. 현금, 보유 평가액, 장 마감 시 외부 입출금, 기록 완전 여부, 장부 대사 여부를 담아요. */
    public record DailyEquity(LocalDate date, BigDecimal cash, BigDecimal positionValue,
            BigDecimal externalFlowAtClose, boolean complete, boolean ledgerReconciled) { }

    /** 청산된 거래 1건의 청산일과 순손익(비용 차감 후)이에요. */
    public record ClosedTrade(LocalDate closedOn, BigDecimal netPnl) { }

    /** 보고서에 들어가는 하루치 점(자산, 일간 수익률 %, 누적 수익률 %)이에요. */
    public record DailyPoint(LocalDate date, BigDecimal equity, BigDecimal returnRate,
            BigDecimal cumulativeReturnRate) { }

    /** 계좌 성과 계산 결과 전체예요. status/reason으로 계산 가능 여부를 먼저 알려 주고, 나머지는 지표와 일별 포인트예요. */
    public record Report(String status, String reason, int observedTradingDays, int returnObservations,
            BigDecimal initialEquity, BigDecimal endingEquity, BigDecimal cumulativeReturnRate,
            BigDecimal maxDrawdownRate, BigDecimal profitFactor, BigDecimal sharpeRatio, BigDecimal sortinoRatio,
            String riskMetricStatus, String benchmarkStatus, BigDecimal excessReturnRate,
            BigDecimal annualRiskFreeRate, BigDecimal annualMinimumReturnRate, int annualTradingDays,
            List<DailyPoint> dailyPoints) { }
}
