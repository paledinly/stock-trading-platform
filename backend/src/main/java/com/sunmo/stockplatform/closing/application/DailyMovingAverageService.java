package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Comparator;
import java.util.List;

@Service
public class DailyMovingAverageService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final String TIMEFRAME = "1D";

    private final StockCandleRepository candles;

    public DailyMovingAverageService(StockCandleRepository candles) {
        this.candles = candles;
    }

    public DailyMovingAverageFeature calculate(ScannerDetection detection) {
        Instant previousSessionDate = detection.getDetectedAt().atZone(MARKET_ZONE).toLocalDate()
                .minusDays(1)
                .atStartOfDay(MARKET_ZONE)
                .toInstant();
        List<StockCandle> series = candles
                .findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                        detection.getStock().getId(), TIMEFRAME, previousSessionDate)
                .stream()
                .sorted(Comparator.comparing(StockCandle::getStartTime))
                .toList();
        return calculate(series);
    }

    public DailyMovingAverageFeature calculate(List<StockCandle> candles) {
        if (candles.size() < 20) {
            return DailyMovingAverageFeature.empty(candles.size());
        }
        StockCandle latest = candles.getLast();
        BigDecimal lastClose = latest.getClose();
        BigDecimal ma5 = average(candles, 5, 0);
        BigDecimal ma20 = average(candles, 20, 0);
        BigDecimal ma60 = candles.size() >= 60 ? average(candles, 60, 0) : null;
        BigDecimal previousMa20 = candles.size() >= 21 ? average(candles, 20, 1) : null;
        BigDecimal ma20Slope = previousMa20 == null ? null : distance(ma20, previousMa20);
        boolean ma5AboveMa20 = ma5.compareTo(ma20) > 0;
        boolean closeAboveMa20 = lastClose.compareTo(ma20) > 0;
        boolean ma20Rising = ma20Slope != null && ma20Slope.signum() > 0;
        boolean bullishAlignment = ma60 == null
                ? ma5AboveMa20 && closeAboveMa20
                : closeAboveMa20 && ma5.compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0;
        BigDecimal ma20Distance = distance(lastClose, ma20);
        return new DailyMovingAverageFeature(
                true,
                candles.size(),
                latest.getStartTime().atZone(MARKET_ZONE).toLocalDate(),
                lastClose,
                ma5,
                ma20,
                ma60,
                distance(lastClose, ma5),
                ma20Distance,
                distance(lastClose, ma60),
                ma20Slope,
                closeAboveMa20,
                ma5AboveMa20,
                ma20Rising,
                bullishAlignment,
                ma20Distance != null && ma20Distance.compareTo(BigDecimal.valueOf(12)) > 0);
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
