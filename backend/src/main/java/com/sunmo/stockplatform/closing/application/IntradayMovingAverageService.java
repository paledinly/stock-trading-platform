package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
public class IntradayMovingAverageService {
    private static final String TIMEFRAME = "5M";

    private final StockCandleRepository candles;

    public IntradayMovingAverageService(StockCandleRepository candles) {
        this.candles = candles;
    }

    public IntradayMovingAverageFeature calculate(ScannerDetection detection) {
        return calculate(detection.getStock().getId(), detection.getDetectedAt());
    }

    public IntradayMovingAverageFeature calculate(Long stockId, java.time.Instant at) {
        List<StockCandle> series = candles
                .findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                        stockId, TIMEFRAME, at)
                .stream()
                .sorted(Comparator.comparing(StockCandle::getStartTime))
                .toList();
        return calculate(series);
    }

    public IntradayMovingAverageFeature calculate(List<StockCandle> candles) {
        if (candles.size() < 20) {
            return IntradayMovingAverageFeature.empty(candles.size());
        }
        StockCandle latest = candles.getLast();
        BigDecimal lastClose = latest.getClose();
        BigDecimal ma5 = average(candles, 5, 0);
        BigDecimal ma20 = average(candles, 20, 0);
        BigDecimal ma60 = candles.size() >= 60 ? average(candles, 60, 0) : null;
        BigDecimal previousMa5 = candles.size() >= 21 ? average(candles, 5, 1) : null;
        BigDecimal previousMa20 = candles.size() >= 21 ? average(candles, 20, 1) : null;
        boolean bullishAlignment = ma60 == null
                ? ma5.compareTo(ma20) > 0
                : ma5.compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0;
        boolean goldenCross = previousMa5 != null && previousMa20 != null
                && previousMa5.compareTo(previousMa20) <= 0 && ma5.compareTo(ma20) > 0;
        boolean ma20Support = latest.getLow().compareTo(ma20) <= 0 && latest.getClose().compareTo(ma20) >= 0;
        boolean ma20Broken = latest.getClose().compareTo(ma20) < 0;
        return new IntradayMovingAverageFeature(
                true,
                candles.size(),
                lastClose,
                ma5,
                ma20,
                ma60,
                distance(lastClose, ma5),
                distance(lastClose, ma20),
                distance(lastClose, ma60),
                bullishAlignment,
                goldenCross,
                ma20Support,
                ma20Broken);
    }

    private BigDecimal average(List<StockCandle> candles, int period, int offsetFromEnd) {
        int endExclusive = candles.size() - offsetFromEnd;
        int start = endExclusive - period;
        return candles.subList(start, endExclusive).stream()
                .map(StockCandle::getClose)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal distance(BigDecimal price, BigDecimal average) {
        if (price == null || average == null || average.signum() == 0) {
            return null;
        }
        return price.subtract(average)
                .divide(average, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(6, RoundingMode.HALF_UP);
    }
}
