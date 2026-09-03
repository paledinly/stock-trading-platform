package com.sunmo.stockplatform.scanner.application;

import com.sunmo.stockplatform.candle.application.CandleSnapshot;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.domain.*;
import org.springframework.stereotype.Component;

import java.math.*;
import java.util.List;

@Component
public class ScannerEvaluator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HIGH_DISTANCE_LIMIT = new BigDecimal("0.300000");
    private static final BigDecimal PULLBACK_DISTANCE_LIMIT = new BigDecimal("1.000000");

    public record Metrics(boolean ready, BigDecimal changeRate, BigDecimal volumeRatio, BigDecimal score,
            String reason, BigDecimal previousHigh, BigDecimal previousLow) {
        public Metrics(boolean ready, BigDecimal changeRate, BigDecimal volumeRatio, BigDecimal score, String reason) {
            this(ready, changeRate, volumeRatio, score, reason, null, null);
        }
    }

    public record Decision(boolean matched, BigDecimal score, String reasonJson) {
    }

    public Metrics calculate(CandleSnapshot current, List<StockCandle> previous) {
        if (previous.size() < 6)
            return new Metrics(false, null, null, null, "INSUFFICIENT_HISTORY");
        BigDecimal sum = previous.stream().map(c -> BigDecimal.valueOf(c.getVolume())).reduce(ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(6), 6, RoundingMode.HALF_UP);
        if (average.signum() == 0)
            return new Metrics(false, null, null, null, "UNDEFINED_BASELINE");
        BigDecimal ratio = BigDecimal.valueOf(current.volume()).divide(average, 6, RoundingMode.HALF_UP);
        BigDecimal base = previous.getFirst().getClose();
        if (base.signum() <= 0)
            return new Metrics(false, null, ratio, null, "INVALID_BASELINE");
        BigDecimal change = current.close().subtract(base).divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
        BigDecimal previousHigh = previous.stream().map(StockCandle::getHigh).reduce(BigDecimal::max).orElse(null);
        BigDecimal previousLow = previous.stream().map(StockCandle::getLow).reduce(BigDecimal::min).orElse(null);
        return new Metrics(true, change, ratio, change.add(ratio).setScale(6, RoundingMode.HALF_UP),
                "READY", previousHigh, previousLow);
    }

    public boolean matches(ScannerSetting setting, Metrics metrics, CandleSnapshot current, BigDecimal daily) {
        return evaluate(setting, metrics, current, daily, null).matched();
    }

    public Decision evaluate(ScannerSetting setting, Metrics metrics, CandleSnapshot current, BigDecimal daily,
            MarketFeatureSnapshot feature) {
        String gate = gate(setting, metrics, current, daily);
        if (gate != null)
            return new Decision(false, metrics.score(), reason(setting, metrics, feature, gate));
        boolean matched = switch (setting.getType()) {
            case VOLUME -> atLeast(metrics.volumeRatio(), setting.getMinVolumeRatio());
            case PRICE_RISE -> atLeast(metrics.changeRate(), setting.getMinChangeRate());
            case MOMENTUM -> atLeast(metrics.volumeRatio(), setting.getMinVolumeRatio())
                    && atLeast(metrics.changeRate(), setting.getMinChangeRate());
            case VOLUME_BREAKOUT ->
                atLeast(featureValue(feature == null ? null : feature.volumeRatio(), metrics.volumeRatio()),
                        setting.getMinVolumeRatio());
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

    private String reason(ScannerSetting setting, Metrics metrics, MarketFeatureSnapshot feature, String state) {
        return "{"
                + "\"version\":\"scanner-reason-v1\","
                + "\"type\":\"" + setting.getType() + "\","
                + "\"state\":\"" + state + "\","
                + "\"changeRate\":\"" + value(metrics.changeRate()) + "\","
                + "\"volumeRatio\":\"" + value(metrics.volumeRatio()) + "\","
                + "\"previousHigh\":\"" + value(metrics.previousHigh()) + "\","
                + "\"vwap\":\"" + value(feature == null ? null : feature.vwap()) + "\","
                + "\"vwapDistanceRate\":\"" + value(feature == null ? null : feature.vwapDistanceRate()) + "\","
                + "\"turnoverRatio\":\"" + value(feature == null ? null : feature.turnoverRatio()) + "\","
                + "\"tradeStrength\":\"" + value(feature == null ? null : feature.tradeStrength()) + "\","
                + "\"dayHighDistanceRate\":\"" + value(feature == null ? null : feature.dayHighDistanceRate()) + "\","
                + "\"featureVersion\":\"" + (feature == null ? "" : feature.featureVersion()) + "\""
                + "}";
    }

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
}
