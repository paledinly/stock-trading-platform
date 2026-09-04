package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightPerformanceResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.TrackPerformanceResponse;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import com.sunmo.stockplatform.closing.domain.OvernightPerformanceStatus;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OvernightPerformanceService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final String TIMEFRAME = "5M";

    private final ClosingRecommendationRepository recommendations;
    private final OvernightPerformanceRepository performances;
    private final StockCandleRepository candles;

    public OvernightPerformanceService(ClosingRecommendationRepository recommendations,
            OvernightPerformanceRepository performances, StockCandleRepository candles) {
        this.recommendations = recommendations;
        this.performances = performances;
        this.candles = candles;
    }

    @Transactional
    public TrackPerformanceResponse track(LocalDate date, BigDecimal targetRate, BigDecimal stopRate) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE).minusDays(1) : date;
        BigDecimal target = targetRate == null ? bd("3") : targetRate;
        BigDecimal stop = stopRate == null ? bd("-2") : stopRate;
        Instant evaluatedAt = Instant.now();
        List<ClosingRecommendation> rows = recommendations.findByRecommendationDateOrderByRankAsc(targetDate);
        List<OvernightPerformance> saved = rows.stream()
                .map(recommendation -> evaluate(recommendation, target, stop))
                .map(performances::save)
                .toList();
        long completed = saved.stream().filter(item -> item.getStatus() == OvernightPerformanceStatus.COMPLETED).count();
        long missing = saved.stream().filter(item -> item.getStatus() == OvernightPerformanceStatus.DATA_MISSING).count();
        return new TrackPerformanceResponse(
                targetDate,
                evaluatedAt,
                rows.size(),
                (int) completed,
                (int) missing,
                target,
                stop,
                OvernightPerformance.VERSION,
                saved.stream().map(OvernightPerformanceResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public List<OvernightPerformanceResponse> list(LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE).minusDays(1) : date;
        return performances.findByRecommendationDate(targetDate).stream()
                .map(OvernightPerformanceResponse::from)
                .toList();
    }

    private OvernightPerformance evaluate(ClosingRecommendation recommendation, BigDecimal targetRate,
            BigDecimal stopRate) {
        OvernightPerformance performance = performances.findByRecommendationId(recommendation.getId())
                .orElseGet(() -> new OvernightPerformance(recommendation));
        List<StockCandle> nextSession = nextSessionCandles(recommendation);
        if (nextSession.isEmpty()) {
            performance.markDataMissing();
            return performance;
        }
        BigDecimal base = recommendation.getBuyReferencePrice();
        BigDecimal open = nextSession.getFirst().getOpen();
        BigDecimal high = nextSession.stream().map(StockCandle::getHigh).reduce(BigDecimal::max).orElse(open);
        BigDecimal low = nextSession.stream().map(StockCandle::getLow).reduce(BigDecimal::min).orElse(open);
        BigDecimal close = nextSession.getLast().getClose();
        BigDecimal maxReturn = pct(high, base);
        BigDecimal maxDrawdown = pct(low, base);
        performance.complete(
                nextSession.getFirst().getStartTime().atZone(MARKET_ZONE).toLocalDate(),
                open,
                high,
                low,
                close,
                pct(open, base),
                pct(close, base),
                maxReturn,
                maxDrawdown,
                maxReturn != null && maxReturn.compareTo(targetRate) >= 0,
                maxDrawdown != null && maxDrawdown.compareTo(stopRate) <= 0);
        return performance;
    }

    private List<StockCandle> nextSessionCandles(ClosingRecommendation recommendation) {
        Instant from = recommendation.getRecommendationDate().plusDays(1).atStartOfDay(MARKET_ZONE).toInstant();
        Instant to = recommendation.getRecommendationDate().plusDays(8).atStartOfDay(MARKET_ZONE).toInstant();
        List<StockCandle> values = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        recommendation.getStock().getId(), TIMEFRAME, from, to)
                .stream()
                .filter(StockCandle::isFinalCandle)
                .toList();
        if (values.isEmpty())
            return List.of();
        LocalDate nextDate = values.getFirst().getStartTime().atZone(MARKET_ZONE).toLocalDate();
        return values.stream()
                .filter(candle -> candle.getStartTime().atZone(MARKET_ZONE).toLocalDate().equals(nextDate))
                .collect(Collectors.toList());
    }

    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() == 0)
            return null;
        return price.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(bd("100"))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
