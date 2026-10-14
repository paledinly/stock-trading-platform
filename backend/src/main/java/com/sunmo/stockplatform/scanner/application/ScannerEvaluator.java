/*
 * 5분봉과 그 앞 6개 봉을 비교해 변화율·거래량 배수 같은 지표를 계산하고, 스캐너 설정의 조건을 만족하는지 판정하는 파일이에요.
 * 판정 근거는 나중에 확인할 수 있도록 JSON 문자열로 남겨요.
 * 실시간 감지(ScannerEngine)와 백테스트(BacktestService)가 같은 규칙을 쓰도록 함께 사용해요.
 */
package com.sunmo.stockplatform.scanner.application;

import com.sunmo.stockplatform.candle.application.CandleSnapshot;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.domain.*;
import org.springframework.stereotype.Component;

import java.math.*;
import java.util.List;

/**
 * 지표 계산과 조건 판정을 맡는 채점자 클래스예요.
 * 시험 채점자가 답안(현재 봉)을 기준표(설정)와 비교해 합격/불합격과 채점 근거를 적어 주는 것과 같아요.
 * 직접 저장하거나 알림을 보내지는 않아요.
 */
@Component
public class ScannerEvaluator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    // 고점 돌파 점수에서 '당일 고가와의 거리(%)' 보정에 쓰는 기준 거리예요.
    private static final BigDecimal HIGH_DISTANCE_LIMIT = new BigDecimal("0.300000");
    // 눌림 후 재돌파에서 VWAP(거래량 가중 평균가)와의 거리(%)가 이 값 이하여야 인정해요.
    private static final BigDecimal PULLBACK_DISTANCE_LIMIT = new BigDecimal("1.000000");

    /**
     * 지표 계산 결과예요.
     * ready(계산 가능 여부), changeRate(기준 봉 대비 변화율 %), volumeRatio(앞 6봉 평균 대비 거래량 배수),
     * score(변화율 + 배수 단순 합), reason(준비 상태 코드), previousHigh/previousLow(앞 6봉 최고가/최저가)를 담아요.
     */
    public record Metrics(boolean ready, BigDecimal changeRate, BigDecimal volumeRatio, BigDecimal score,
            String reason, BigDecimal previousHigh, BigDecimal previousLow) {
        /**
         * 하는 일: 앞 6봉 고가/저가 없이 Metrics를 만드는 짧은 생성자예요.
         * 받는 것: ready, changeRate, volumeRatio, score, reason.
         * 돌려주는 것: previousHigh/previousLow가 비어 있는 Metrics.
         */
        public Metrics(boolean ready, BigDecimal changeRate, BigDecimal volumeRatio, BigDecimal score, String reason) {
            this(ready, changeRate, volumeRatio, score, reason, null, null);
        }
    }

    /**
     * 판정 결과예요. matched(조건 충족 여부), score(정렬용 점수), reasonJson(판정 근거 JSON)을 담아요.
     */
    public record Decision(boolean matched, BigDecimal score, String reasonJson) {
    }

    /**
     * 하는 일: 현재 확정봉과 바로 앞 5분봉 6개로 지표를 계산해요.
     * 받는 것: current(현재 5분봉), previous(앞선 5분봉 목록, 6개를 기대해요).
     * 돌려주는 것: Metrics. 기준선이 부족하거나 끊겨 있으면 ready=false와 이유 코드를 담아요.
     */
    public Metrics calculate(CandleSnapshot current, List<StockCandle> previous) {
        // 준비: 확정봉인지, 기준 봉이 6개인지, 같은 장 날짜에 5분 간격으로 빈틈없이 이어지는지 확인해요.
        if (!current.finalCandle())
            return new Metrics(false, null, null, null, "CURRENT_WINDOW_INCOMPLETE");
        if (previous.size() < 6)
            return new Metrics(false, null, null, null, "INSUFFICIENT_HISTORY");
        List<StockCandle> ordered = previous.stream().sorted(java.util.Comparator.comparing(StockCandle::getStartTime))
                .toList();
        java.time.ZoneId marketZone = java.time.ZoneId.of("Asia/Seoul");
        if (ordered.stream().anyMatch(row -> !row.isFinalCandle()
                || !row.getStartTime().atZone(marketZone).toLocalDate()
                        .equals(current.startTime().atZone(marketZone).toLocalDate())))
            return new Metrics(false, null, null, null, "INVALID_BASELINE");
        for (int index = 1; index < ordered.size(); index++) {
            if (!ordered.get(index).getStartTime().equals(ordered.get(index - 1).getStartTime().plusSeconds(300)))
                return new Metrics(false, null, null, null, "NON_CONTIGUOUS_BASELINE");
        }
        if (!ordered.getLast().getStartTime().plusSeconds(300).equals(current.startTime()))
            return new Metrics(false, null, null, null, "NON_CONTIGUOUS_BASELINE");
        // 처리: 앞 6봉 평균 거래량 대비 현재 거래량 배수를 구해요.
        BigDecimal sum = ordered.stream().map(c -> BigDecimal.valueOf(c.getVolume())).reduce(ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(6), 6, RoundingMode.HALF_UP);
        if (average.signum() == 0)
            return new Metrics(false, null, null, null, "UNDEFINED_BASELINE");
        BigDecimal ratio = BigDecimal.valueOf(current.volume()).divide(average, 6, RoundingMode.HALF_UP);
        // 변화율은 앞 6봉 중 가장 오래된 봉의 종가(약 30분 전) 대비 현재 종가로 계산해요.
        // ⚠️ 확인 필요: 저장 컬럼 이름은 five_minute_change_rate(5분 변화율)지만 실제로는 약 30분 구간 변화율이에요.
        BigDecimal base = ordered.getFirst().getClose();
        if (base.signum() <= 0)
            return new Metrics(false, null, ratio, null, "INVALID_BASELINE");
        BigDecimal change = current.close().subtract(base).divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
        BigDecimal previousHigh = ordered.stream().map(StockCandle::getHigh).reduce(BigDecimal::max).orElse(null);
        BigDecimal previousLow = ordered.stream().map(StockCandle::getLow).reduce(BigDecimal::min).orElse(null);
        // 결과: 기본 점수는 변화율과 거래량 배수의 단순 합이에요(상승 확률이 아니에요).
        return new Metrics(true, change, ratio, change.add(ratio).setScale(6, RoundingMode.HALF_UP),
                "READY", previousHigh, previousLow);
    }

    /**
     * 하는 일: 특징값 없이 조건 충족 여부만 확인해요.
     * 받는 것: setting, metrics, current(현재 봉), daily(당일 누적 거래대금).
     * 돌려주는 것: 충족하면 true.
     */
    public boolean matches(ScannerSetting setting, Metrics metrics, CandleSnapshot current, BigDecimal daily) {
        return evaluate(setting, metrics, current, daily, null).matched();
    }

    /**
     * 하는 일: 공통 문턱(가격·거래대금)을 먼저 보고, 통과하면 스캐너 종류별 조건을 판정해요.
     * 받는 것: setting, metrics, current, daily(당일 누적 거래대금, 없으면 null), feature(VWAP·체결강도 등 특징값, 없으면 null).
     * 돌려주는 것: Decision(충족 여부, 점수, 근거 JSON).
     */
    public Decision evaluate(ScannerSetting setting, Metrics metrics, CandleSnapshot current, BigDecimal daily,
            MarketFeatureSnapshot feature) {
        String gate = gate(setting, metrics, current, daily);
        if (gate != null)
            return new Decision(false, metrics.score(), reason(setting, metrics, feature, gate));
        // 처리: 종류별 조건이에요. 특징값이 필요한 종류는 feature가 없으면 미충족이에요.
        // TURNOVER_BREAKOUT은 거래대금 배수 기준으로 minVolumeRatio를, VWAP 계열은 VWAP 거리 기준으로 minChangeRate를 재사용해요.
        boolean matched = switch (setting.getType()) {
            case VOLUME -> atLeast(metrics.volumeRatio(), setting.getMinVolumeRatio());
            case PRICE_RISE -> atLeast(metrics.changeRate(), setting.getMinChangeRate());
            case MOMENTUM -> atLeast(metrics.volumeRatio(), setting.getMinVolumeRatio())
                    && atLeast(metrics.changeRate(), setting.getMinChangeRate());
            case VOLUME_BREAKOUT ->
                atLeast(metrics.volumeRatio(), setting.getMinVolumeRatio());
            case TURNOVER_BREAKOUT -> feature != null && atLeast(feature.turnoverRatio(), setting.getMinVolumeRatio());
            case HIGH_BREAKOUT -> metrics.previousHigh() != null && current.high().compareTo(metrics.previousHigh()) > 0
                    && atLeast(metrics.changeRate(), setting.getMinChangeRate());
            case VWAP_BREAKOUT -> feature != null && atLeast(feature.vwapDistanceRate(), setting.getMinChangeRate());
            case VWAP_RECLAIM -> feature != null && atLeast(feature.vwapDistanceRate(), ZERO)
                    && atLeast(metrics.changeRate(), setting.getMinChangeRate());
            case PULLBACK_REBREAK -> feature != null && metrics.previousHigh() != null
                    && current.low().compareTo(feature.vwap()) <= 0
                    && current.close().compareTo(metrics.previousHigh()) > 0
                    && atMost(abs(feature.vwapDistanceRate()), PULLBACK_DISTANCE_LIMIT);
        };
        return new Decision(matched, score(setting, metrics, feature),
                reason(setting, metrics, feature, matched ? "MATCHED" : "CONDITION_NOT_MET"));
    }

    /**
     * 하는 일: 모든 종류에 공통인 최소 조건(지표 준비, 최소 가격, 5분 거래대금, 당일 거래대금)을 확인해요.
     * 받는 것: setting, metrics, current, daily.
     * 돌려주는 것: 걸린 이유 코드. 모두 통과하면 null.
     */
    private String gate(ScannerSetting setting, Metrics metrics, CandleSnapshot current, BigDecimal daily) {
        if (!metrics.ready())
            return metrics.reason();
        if (current.close().compareTo(setting.getMinPrice()) < 0)
            return "BELOW_MIN_PRICE";
        if (current.tradingValue().compareTo(setting.getMinFiveMinuteTradingValue()) < 0)
            return "BELOW_MIN_5M_VALUE";
        if (daily == null || daily.compareTo(setting.getMinDailyTradingValue()) < 0)
            return "BELOW_MIN_DAILY_VALUE";
        return null;
    }

    /**
     * 하는 일: 기본 점수에 종류별 특징값 보너스를 더해 정렬용 점수를 만들어요.
     * 받는 것: setting, metrics, feature.
     * 돌려주는 것: 소수 6자리 점수. 특징값이 없으면 기본 점수 그대로예요.
     */
    private BigDecimal score(ScannerSetting setting, Metrics metrics, MarketFeatureSnapshot feature) {
        BigDecimal base = metrics.score() == null ? ZERO : metrics.score();
        if (feature == null)
            return base;
        BigDecimal score = switch (setting.getType()) {
            case TURNOVER_BREAKOUT -> add(base, feature.turnoverRatio());
            case VWAP_BREAKOUT, VWAP_RECLAIM -> add(base, feature.vwapDistanceRate());
            case HIGH_BREAKOUT -> add(base, HIGH_DISTANCE_LIMIT.subtract(nullToZero(feature.dayHighDistanceRate())));
            case PULLBACK_REBREAK -> add(base, nullToZero(feature.vwapDistanceRate()));
            default -> base;
        };
        return score.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 판정 근거를 JSON 문자열로 만들어요(버전 scanner-reason-v2).
     * 받는 것: setting, metrics, feature, state(판정 상태 코드).
     * 돌려주는 것: JSON 문자열. 값이 없으면 빈 문자열로 넣어요.
     */
    private String reason(ScannerSetting setting, Metrics metrics, MarketFeatureSnapshot feature, String state) {
        return "{"
                + "\"version\":\"scanner-reason-v2\","
                + "\"type\":\"" + setting.getType() + "\","
                + "\"state\":\"" + state + "\","
                + "\"changeRate\":\"" + value(metrics.changeRate()) + "\","
                + "\"volumeRatio\":\"" + value(metrics.volumeRatio()) + "\","
                + "\"previousHigh\":\"" + value(metrics.previousHigh()) + "\","
                + "\"vwap\":\"" + value(feature == null ? null : feature.vwap()) + "\","
                + "\"vwapDistanceRate\":\"" + value(feature == null ? null : feature.vwapDistanceRate()) + "\","
                + "\"fiveMinuteTradingValueRatio\":\"" + value(feature == null ? null : feature.turnoverRatio()) + "\","
                + "\"orderFlowWindow\":\"LAST_TICK_DELTA\","
                + "\"triggerSemantics\":\"" + triggerSemantics(setting.getType()) + "\","
                + "\"tradeStrength\":\"" + value(feature == null ? null : feature.tradeStrength()) + "\","
                + "\"dayHighDistanceRate\":\"" + value(feature == null ? null : feature.dayHighDistanceRate()) + "\","
                + "\"featureVersion\":\"" + (feature == null ? "" : feature.featureVersion()) + "\""
                + "}";
    }

    /**
     * 아래는 null을 안전하게 다루는 작은 계산 도우미들이에요.
     * atLeast/atMost: 값이 있고 기준 이상/이하인지, featureValue: 값이 없으면 대체값(지금은 쓰이지 않는 것으로 보여요),
     * add: null을 0으로 보고 더하기, nullToZero: null을 0으로, abs: 절댓값(null은 그대로), value: JSON용 문자열(null이면 빈 문자열).
     */
    private boolean atLeast(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private boolean atMost(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) <= 0;
    }

    private BigDecimal featureValue(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private BigDecimal add(BigDecimal left, BigDecimal right) {
        return nullToZero(left).add(nullToZero(right));
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }

    private String value(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /**
     * 하는 일: 근거 JSON에 넣을 '무엇을 신호로 봤는지' 설명 코드를 정해요.
     * 받는 것: type(스캐너 종류).
     * 돌려주는 것: 설명 코드. 대부분은 종류 이름 그대로예요.
     */
    private String triggerSemantics(ScannerType type) {
        return switch (type) {
            case VWAP_RECLAIM -> "ABOVE_VWAP_WITH_POSITIVE_WINDOW_CHANGE";
            case PULLBACK_REBREAK -> "SAME_CANDLE_VWAP_TOUCH_AND_PREVIOUS_HIGH_BREAK";
            default -> type.name();
        };
    }
}
