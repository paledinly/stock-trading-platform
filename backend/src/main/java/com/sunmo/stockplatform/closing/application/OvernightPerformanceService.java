package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.*;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OvernightPerformanceService {
    private final ClosingRecommendationRepository recommendations;
    private final OvernightPerformanceRepository performances;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final ObjectMapper mapper;

    public OvernightPerformanceService(ClosingRecommendationRepository recommendations,
            OvernightPerformanceRepository performances, StockCandleRepository candles,
            ClosingTradingCalendar calendar, ObjectMapper mapper) {
        this.recommendations = recommendations;
        this.performances = performances;
        this.candles = candles;
        this.calendar = calendar;
        this.mapper = mapper;
    }

    @Transactional
    public TrackPerformanceResponse track(LocalDate date, BigDecimal targetRate, BigDecimal stopRate, Long runId) {
        LocalDate targetDate = date == null ? calendar.today().minusDays(1) : date;
        BigDecimal target = targetRate == null ? new BigDecimal("3") : targetRate;
        BigDecimal stop = stopRate == null ? new BigDecimal("-2") : stopRate;
        if (target.signum() <= 0 || stop.signum() >= 0 || stop.compareTo(new BigDecimal("-100")) <= 0)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Invalid target/stop rates");
        List<ClosingRecommendation> rows = runId == null
                ? recommendations.findByRecommendationDateOrderByRankAsc(targetDate)
                : recommendations.findByRunIdOrderByRankAsc(runId);
        Instant now = calendar.now();
        List<OvernightPerformance> saved = rows.stream().map(row -> {
            ClosingRecommendation locked = recommendations.findLockedById(row.getId()).orElseThrow();
            return evaluate(locked, target, stop, now);
        }).toList();
        return new TrackPerformanceResponse(targetDate, now, rows.size(),
                (int) saved.stream().filter(p -> p.getStatus() == OvernightPerformanceStatus.COMPLETED
                        && OvernightPerformance.OBSERVATION_VERSION.equals(p.getCalculationVersion())).count(),
                (int) saved.stream().filter(p -> p.getStatus() == OvernightPerformanceStatus.DATA_INCOMPLETE
                        || p.getStatus() == OvernightPerformanceStatus.DATA_MISSING).count(),
                target, stop, OvernightPerformance.OBSERVATION_VERSION,
                saved.stream().map(OvernightPerformanceResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public List<OvernightPerformanceResponse> list(LocalDate date, Long runId) {
        LocalDate targetDate = date == null ? calendar.today().minusDays(1) : date;
        return (runId == null ? performances.findByRecommendationDate(targetDate)
                : performances.findByRecommendationRunIdOrderByRecommendationRankAsc(runId))
                .stream().map(OvernightPerformanceResponse::from).toList();
    }

    private OvernightPerformance evaluate(ClosingRecommendation recommendation, BigDecimal target, BigDecimal stop, Instant now) {
        Optional<OvernightPerformance> existing = performances.findByRecommendationId(recommendation.getId());
        if (existing.isPresent()) {
            OvernightPerformance old = existing.get();
            if (!OvernightPerformance.OBSERVATION_VERSION.equals(old.getCalculationVersion())) return old;
            if (old.getTargetRate().compareTo(target) != 0 || old.getStopRate().compareTo(stop) != 0)
                throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                        "Observation thresholds are fixed. Create a new evaluation run to change them.");
            if (old.getStatus() == OvernightPerformanceStatus.COMPLETED) return old;
        }
        OvernightPerformance result = existing.orElseGet(() -> new OvernightPerformance(recommendation));
        if (existing.isEmpty()) {
            LocalDate next = calendar.nextTradingDay(recommendation.getRecommendationDate());
            result.initializeObservation(next, calendar.open(next), calendar.close(next), target, stop);
        }
        Instant open = result.getSessionOpen();
        Instant close = result.getSessionClose();
        List<StockCandle> series = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        recommendation.getStock().getId(), "5M", open, close.plusSeconds(1))
                .stream().filter(StockCandle::isFinalCandle)
                .filter(c -> !c.getStartTime().isBefore(open) && !c.getStartTime().isAfter(close))
                .filter(c -> Duration.between(open, c.getStartTime()).getSeconds() % 300 == 0)
                .filter(c -> !end(c.getStartTime(), close).isAfter(now))
                .filter(this::validPrices).sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        Map<Instant, Long> counts = series.stream().collect(Collectors.groupingBy(StockCandle::getStartTime, Collectors.counting()));
        List<Instant> missing = new ArrayList<>();
        // The aggregator puts the 15:30 closing print in its own bucket; require it too.
        for (Instant start = open; !start.isAfter(close) && !end(start, close).isAfter(now); start = start.plusSeconds(300)) {
            if (counts.getOrDefault(start, 0L) != 1) missing.add(start);
        }
        OvernightPerformanceStatus state = now.isBefore(open) ? OvernightPerformanceStatus.PENDING
                : !missing.isEmpty() ? OvernightPerformanceStatus.DATA_INCOMPLETE
                : now.isBefore(close) ? OvernightPerformanceStatus.IN_PROGRESS : OvernightPerformanceStatus.COMPLETED;
        BigDecimal base = recommendation.getBuyReferencePrice();
        BigDecimal first = series.isEmpty() || !series.getFirst().getStartTime().equals(open) ? null : series.getFirst().getOpen();
        BigDecimal latest = series.isEmpty() ? null : series.getLast().getClose();
        BigDecimal high = series.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null);
        BigDecimal low = series.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null);
        BigDecimal maximum = pct(high, base), minimum = pct(low, base);
        String gaps;
        try { gaps = mapper.writeValueAsString(missing.stream().map(Instant::toString).toList()); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Failed to preserve coverage", error); }
        result.observe(now, series.isEmpty() ? null : end(series.getLast().getStartTime(), close), gaps, state,
                first, high, low, latest, pct(first, base), pct(latest, base), maximum, minimum,
                maximum != null && maximum.compareTo(target) >= 0, minimum != null && minimum.compareTo(stop) <= 0);
        return performances.save(result);
    }

    private Instant end(Instant start, Instant close) {
        Instant end = start.plusSeconds(300);
        return end.isAfter(close) ? close : end;
    }

    private boolean validPrices(StockCandle c) {
        return c.getOpen() != null && c.getHigh() != null && c.getLow() != null && c.getClose() != null
                && c.getLow().signum() > 0 && c.getHigh().compareTo(c.getOpen().max(c.getClose())) >= 0
                && c.getLow().compareTo(c.getOpen().min(c.getClose())) <= 0;
    }

    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() <= 0) return null;
        return price.subtract(base).divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
    }
}
