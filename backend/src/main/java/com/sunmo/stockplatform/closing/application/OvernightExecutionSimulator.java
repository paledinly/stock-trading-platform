/*
 * 다음 거래일 5분봉을 따라가며 가상 체결(실제 주문 없이 "이 가격에 샀다/팔았다"고 계산만 하는 것)로 매도 시점과 수익률을 계산하는 계산기예요.
 * 목표가(+3% 등)·손절가(-2% 등) 도달, 시가 갭(전날보다 크게 뛰거나 빠져서 시작하는 것), 장 마감 시 청산을 판정하고 수수료·세금·슬리피지를 뺀 순수익률도 구해요.
 * 다음날 성과 추적(OvernightPerformanceService), 후보 관측(ClosingCandidateObservationService), 백테스트(OvernightBacktestService)가 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.closing.config.TradingCostProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 가상 진입 가격과 다음날 봉들로 "언제, 얼마에, 왜 팔았는지"를 계산하는 클래스예요.
 * 미리 정한 규칙(익절선·손절선)대로 움직이는 자동 운전 연습 프로그램처럼, 봉을 한 칸씩 넘기며 어느 선에 먼저 닿았는지 보고 판정해요.
 * 한 봉 안에서 익절선과 손절선을 모두 건드리면 순서를 알 수 없어 보수적으로 손절로 쳐요.
 */
@Component
public class OvernightExecutionSimulator {
    // 결과에 남기는 체결 모델 버전 이름이에요.
    public static final String VERSION = "overnight-execution-v1";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final TradingCostProperties costs;

    /**
     * 하는 일: 거래 비용 설정을 받아 계산기를 만들어요.
     * 받는 것: 수수료·세금·슬리피지(원하는 가격보다 불리하게 체결되는 차이) 설정(TradingCostProperties).
     * 돌려주는 것: 없음(생성자).
     */
    public OvernightExecutionSimulator(TradingCostProperties costs) {
        this.costs = costs;
    }

    /**
     * 하는 일: 익절(목표 수익에서 팔기)/손절(손실 한도에서 팔기) 규칙으로 다음날 봉을 차례로 보며 매도 시점을 정해요.
     * 받는 것: 진입 기준가, 다음날 5분봉 목록(시간순), 목표 수익률(%), 손절 수익률(%, 음수).
     * 돌려주는 것: 체결 결과. 진입가가 없거나 0 이하, 봉이 비어 있으면 null.
     *
     * Callers pass a series that is complete under {@link NextSessionCoverage}, so its last bar is the 15:25
     * closing-print bar and TIME_EXIT uses the 15:30 closing auction price. exitAt is the exiting bar's start.
     */
    public ExecutionResult targetOrStop(BigDecimal entryReference, List<StockCandle> candles,
            BigDecimal targetRate, BigDecimal stopRate) {
        if (entryReference == null || entryReference.signum() <= 0 || candles.isEmpty()) return null;
        // 준비: 진입가에 목표·손절 비율을 곱해 익절가와 손절가를 구해요.
        BigDecimal target = threshold(entryReference, targetRate);
        BigDecimal stop = threshold(entryReference, stopRate);
        for (StockCandle candle : candles) {
            // 봉 시가가 이미 손절가 이하/익절가 이상이면 그 시가에 체결된 것으로 봐요(갭 손절/갭 익절).
            if (candle.getOpen().compareTo(stop) <= 0)
                return exit(entryReference, candle.getStartTime(), candle.getOpen(), "GAP_STOP", false, true, false);
            if (candle.getOpen().compareTo(target) >= 0)
                return exit(entryReference, candle.getStartTime(), candle.getOpen(), "GAP_TARGET", true, false, false);
            // 봉 안에서 고가·저가가 선을 건드렸는지 봐요. 둘 다면 순서를 알 수 없어 손절가로 처리해요(AMBIGUOUS_STOP).
            boolean targetHit = candle.getHigh().compareTo(target) >= 0;
            boolean stopHit = candle.getLow().compareTo(stop) <= 0;
            if (targetHit && stopHit)
                return exit(entryReference, candle.getStartTime(), stop, "AMBIGUOUS_STOP", true, true, true);
            if (stopHit)
                return exit(entryReference, candle.getStartTime(), stop, "STOP", false, true, false);
            if (targetHit)
                return exit(entryReference, candle.getStartTime(), target, "TARGET", true, false, false);
        }
        // 결과: 끝까지 아무 선에도 닿지 않으면 마지막 봉(15:25 종가 봉)의 종가로 시간 청산(TIME_EXIT)해요.
        StockCandle last = candles.getLast();
        return exit(entryReference, last.getStartTime(), last.getClose(), "TIME_EXIT", false, false, false);
    }

    /**
     * 하는 일: 진입가와 청산가로 비용 전·후 수익률을 계산해 체결 결과를 만들어요.
     * 받는 것: 진입가, 청산 봉 시작 시각, 청산가, 청산 이유, 익절/손절/애매함 여부.
     * 돌려주는 것: 체결 결과. 진입가가 없거나 0 이하, 청산가가 없으면 null.
     */
    public ExecutionResult exit(BigDecimal entryReference, Instant exitAt, BigDecimal exitReference,
            String reason, boolean targetHit, boolean stopHit, boolean ambiguous) {
        if (entryReference == null || entryReference.signum() <= 0 || exitReference == null) return null;
        // 비용 전 수익률(gross)을 먼저 구하고, 매수는 (수수료+슬리피지)만큼 비싸게, 매도는 (수수료+슬리피지+세금)만큼 싸게 잡아 순수익률(net)을 구해요.
        BigDecimal gross = rate(exitReference, entryReference);
        BigDecimal buyCash = entryReference.multiply(BigDecimal.ONE.add(percent(
                costs.buySlippagePercent().add(costs.buyFeePercent()))));
        BigDecimal sellCash = exitReference.multiply(BigDecimal.ONE.subtract(percent(
                costs.sellSlippagePercent().add(costs.sellFeePercent()).add(costs.sellTaxPercent()))));
        return new ExecutionResult(entryReference, exitReference, exitAt, gross, rate(sellCash, buyCash),
                targetHit, stopHit, ambiguous, reason, costs.applied(), VERSION);
    }

    /**
     * 하는 일: 적용한 비용 가정을 JSON 문자열로 만들어 결과와 함께 남길 수 있게 해요.
     * 받는 것: 없음. 돌려주는 것: 수수료·세금·슬리피지 비율(%)을 담은 JSON 문자열.
     */
    public String costAssumption() {
        return "{\"buyFeePercent\":\"" + costs.buyFeePercent() + "\",\"sellFeePercent\":\""
                + costs.sellFeePercent() + "\",\"sellTaxPercent\":\"" + costs.sellTaxPercent()
                + "\",\"buySlippagePercent\":\"" + costs.buySlippagePercent()
                + "\",\"sellSlippagePercent\":\"" + costs.sellSlippagePercent() + "\"}";
    }

    /**
     * 하는 일: 기준가에 퍼센트 비율을 적용한 가격을 구해요(예: 기준가×1.03).
     * 받는 것: 기준가, 비율(%). 돌려주는 것: 적용된 가격.
     */
    private BigDecimal threshold(BigDecimal base, BigDecimal rate) {
        return base.multiply(BigDecimal.ONE.add(percent(rate)));
    }

    /**
     * 하는 일: 퍼센트 값을 소수 비율로 바꿔요(3 → 0.03).
     * 받는 것: 퍼센트 값. 돌려주는 것: 소수 10자리 비율.
     */
    private BigDecimal percent(BigDecimal value) {
        return value.divide(HUNDRED, 10, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 기준 대비 가격의 변화율(%)을 구해요.
     * 받는 것: 가격, 기준값. 돌려주는 것: (가격-기준)/기준×100, 소수 6자리.
     */
    private BigDecimal rate(BigDecimal price, BigDecimal base) {
        return price.subtract(base).divide(base, 10, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 가상 체결 한 건의 결과예요: 진입·청산 기준가, 청산 봉 시작 시각, 비용 전·후 수익률(%),
     * 익절·손절·애매함 여부, 청산 이유, 비용 적용 여부, 계산 모델 버전.
     */
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
