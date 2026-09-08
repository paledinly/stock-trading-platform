package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.*;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.marketwide.infrastructure.*;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MarketCoverageService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final StockRepository stocks;
    private final MarketBroadSnapshotRepository snapshots;
    private final PrecisionSubscriptionSessionRepository subscriptions;
    private final ScannerDetectionRepository detections;
    private final ClosingRecommendationRepository recommendations;
    private final OvernightPerformanceRepository performances;
    private final MarketWideScanRunRepository runs;

    public MarketCoverageService(StockRepository stocks, MarketBroadSnapshotRepository snapshots,
            PrecisionSubscriptionSessionRepository subscriptions, ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations, OvernightPerformanceRepository performances,
            MarketWideScanRunRepository runs) {
        this.stocks = stocks; this.snapshots = snapshots; this.subscriptions = subscriptions;
        this.detections = detections; this.recommendations = recommendations; this.performances = performances;
        this.runs = runs;
    }

    public CoverageResponse coverage(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(MARKET_ZONE) : date;
        long active = stocks.countByActiveTrue();
        long tradable = stocks.countByActiveTrueAndManagedFalseAndTradingHaltedFalseAndEtfFalseAndEtnFalse();
        List<MarketBroadSnapshot> snapshotRows = snapshots.findBySessionDateOrderByCapturedAtAsc(target);
        Map<Long, MarketBroadSnapshot> latestSnapshots = new LinkedHashMap<>();
        snapshotRows.forEach(row -> latestSnapshots.put(row.getStock().getId(), row));
        List<PrecisionSubscriptionSession> subscriptionRows = subscriptions.findBySessionDateOrderByRequestedAtAsc(target);
        Instant measurementEnd = measurementEnd(target);
        List<ClosingRecommendation> recommendationRows = recommendations.findByRecommendationDateOrderByRankAsc(target);
        List<OvernightPerformance> performanceRows = performances.findByRecommendationDate(target);
        List<MarketWideScanRun> runRows = runs.findBySessionDateOrderByScheduledForAsc(target);
        Instant from = target.atStartOfDay(MARKET_ZONE).toInstant();
        Instant to = target.plusDays(1).atStartOfDay(MARKET_ZONE).toInstant();
        int detectionStocks = (int) detections.findByDetectedAtBetweenOrderByDetectedAtAsc(from, to).stream()
                .map(row -> row.getStock().getId()).distinct().count();

        int captured = latestSnapshots.size();
        int collected = (int) latestSnapshots.values().stream()
                .filter(row -> row.getCollectionStatus() == BroadSnapshotStatus.COLLECTED).count();
        int insufficient = captured - collected;
        Set<String> requestedCodes = subscriptionRows.stream().map(PrecisionSubscriptionSession::getStockCode)
                .collect(Collectors.toSet());
        Set<String> activatedCodes = subscriptionRows.stream().filter(row -> row.getActivatedAt() != null)
                .map(PrecisionSubscriptionSession::getStockCode).collect(Collectors.toSet());
        Map<String, Integer> exclusion = exclusionReasons(latestSnapshots.values(), recommendationRows);
        int precisionRecommendations = countSource(recommendationRows, ClosingCandidateSource.PRECISION);
        int broadRecommendations = countSource(recommendationRows, ClosingCandidateSource.BROAD);

        return new CoverageResponse(target, Instant.now(), active, tradable, runRows.size(),
                countRuns(runRows, MarketWideScanRun.Status.COMPLETED), countRuns(runRows, MarketWideScanRun.Status.FAILED),
                captured, rate(captured, tradable), captured, collected, insufficient, rate(collected, tradable),
                requestedCodes.size(), activatedCodes.size(), averageMinutes(subscriptionRows, measurementEnd),
                detectionStocks, broadRecommendations, precisionRecommendations, exclusion,
                List.of(performance(ClosingCandidateSource.BROAD, recommendationRows, performanceRows),
                        performance(ClosingCandidateSource.PRECISION, recommendationRows, performanceRows)),
                List.of("Universe counts use the current stock master, not a historical daily universe.",
                        "Ranking coverage represents stocks persisted as Broad snapshots; ranking non-selected stocks are not observable.",
                        "Performance statistics are descriptive and do not include fees, tax or slippage."));
    }

    private Map<String, Integer> exclusionReasons(Collection<MarketBroadSnapshot> values,
            List<ClosingRecommendation> recommendations) {
        Set<Long> broad = recommendations.stream().filter(r -> r.getCandidateSource() == ClosingCandidateSource.BROAD)
                .map(r -> r.getStock().getId()).collect(Collectors.toSet());
        Set<Long> precision = recommendations.stream().filter(r -> r.getCandidateSource() == ClosingCandidateSource.PRECISION)
                .map(r -> r.getStock().getId()).collect(Collectors.toSet());
        Map<String, Integer> result = new TreeMap<>();
        for (MarketBroadSnapshot row : values) {
            String reason = broad.contains(row.getStock().getId()) ? "RECOMMENDED_BROAD"
                    : precision.contains(row.getStock().getId()) ? "PROMOTED_TO_PRECISION"
                    : row.getCollectionStatus() == BroadSnapshotStatus.QUOTE_FAILED ? "QUOTE_FAILED"
                    : row.getDataQuality() == BroadSnapshotQuality.INSUFFICIENT ? "INSUFFICIENT_DATA"
                    : !tradable(row) ? "NOT_TRADABLE" : "SCORE_OR_LIMIT_FILTERED";
            result.merge(reason, 1, Integer::sum);
        }
        return Map.copyOf(result);
    }

    private boolean tradable(MarketBroadSnapshot row) {
        var stock = row.getStock();
        return stock.isActive() && !stock.isManaged() && !stock.isTradingHalted() && !stock.isEtf() && !stock.isEtn();
    }

    private SourcePerformanceResponse performance(ClosingCandidateSource source,
            List<ClosingRecommendation> recommendations, List<OvernightPerformance> rows) {
        Set<Long> ids = recommendations.stream().filter(r -> r.getCandidateSource() == source)
                .map(ClosingRecommendation::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<OvernightPerformance> relevant = rows.stream()
                .filter(p -> ids.contains(p.getRecommendation().getId())).toList();
        List<OvernightPerformance> completed = relevant.stream()
                .filter(p -> p.getStatus() == OvernightPerformanceStatus.COMPLETED).toList();
        return new SourcePerformanceResponse(source.name(), ids.size(), completed.size(), ids.size() - completed.size(),
                positiveRate(completed, OvernightPerformance::getCloseReturnRate),
                avg(completed, OvernightPerformance::getOpenReturnRate), avg(completed, OvernightPerformance::getCloseReturnRate),
                avg(completed, OvernightPerformance::getMaxReturnRate), avg(completed, OvernightPerformance::getMaxDrawdownRate),
                boolRate(completed, OvernightPerformance::isTargetHit), boolRate(completed, OvernightPerformance::isStopHit));
    }

    private BigDecimal averageMinutes(List<PrecisionSubscriptionSession> rows, Instant end) {
        List<Long> values = rows.stream().filter(row -> row.getActivatedAt() != null)
                .map(row -> Duration.between(row.getActivatedAt(), row.getEndedAt() == null ? end : row.getEndedAt()).toMinutes())
                .map(value -> Math.max(0, value)).toList();
        return values.isEmpty() ? null : BigDecimal.valueOf(values.stream().mapToLong(Long::longValue).average().orElse(0))
                .setScale(3, RoundingMode.HALF_UP);
    }
    private Instant measurementEnd(LocalDate date) {
        Instant close = date.atTime(15, 30).atZone(MARKET_ZONE).toInstant();
        Instant now = Instant.now();
        return date.equals(LocalDate.now(MARKET_ZONE)) && now.isBefore(close) ? now : close;
    }
    private int countSource(List<ClosingRecommendation> rows, ClosingCandidateSource source) {
        return (int) rows.stream().filter(row -> row.getCandidateSource() == source).count();
    }
    private int countRuns(List<MarketWideScanRun> rows, MarketWideScanRun.Status status) {
        return (int) rows.stream().filter(row -> row.getStatus() == status).count();
    }
    private BigDecimal rate(long count, long total) {
        return total == 0 ? null : BigDecimal.valueOf(count).multiply(bd("100"))
                .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
    }
    private BigDecimal positiveRate(List<OvernightPerformance> rows,
            Function<OvernightPerformance, BigDecimal> getter) {
        long count = rows.stream().map(getter).filter(Objects::nonNull).filter(v -> v.signum() > 0).count();
        return rate(count, rows.size());
    }
    private BigDecimal boolRate(List<OvernightPerformance> rows,
            java.util.function.Predicate<OvernightPerformance> predicate) {
        return rate(rows.stream().filter(predicate).count(), rows.size());
    }
    private BigDecimal avg(List<OvernightPerformance> rows, Function<OvernightPerformance, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }
    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
