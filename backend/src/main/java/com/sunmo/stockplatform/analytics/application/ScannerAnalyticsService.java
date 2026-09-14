package com.sunmo.stockplatform.analytics.application;

import com.sunmo.stockplatform.analytics.api.PerformanceDtos.AnalyticsResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.HistoricalEdgeResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.PerformanceResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.SignalCombinationResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.TargetStopSummary;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.TimeBucketResponse;
import com.sunmo.stockplatform.analytics.infrastructure.AnalyticsRow;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
@Transactional(readOnly = true)
public class ScannerAnalyticsService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final ScannerDetectionRepository detections;
    private final DetectionPerformanceRepository performances;
    private final SummaryReadCache cache;

    public ScannerAnalyticsService(ScannerDetectionRepository detections,
            DetectionPerformanceRepository performances) {
        this(detections, performances, new SummaryReadCache(java.time.Duration.ZERO));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScannerAnalyticsService(ScannerDetectionRepository detections,
            DetectionPerformanceRepository performances, SummaryReadCache cache) {
        this.detections = detections;
        this.performances = performances;
        this.cache = cache;
    }

    public PerformanceResponse performance(long detectionId) {
        return performances.findById(detectionId)
                .map(PerformanceResponse::from)
                .orElseThrow(() -> new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.NOT_FOUND,
                        "Performance not found: " + detectionId));
    }

    public AnalyticsResponse analytics(Long settingId, Instant from, Instant to) {
        return analytics(settingId, from, to, bd("3"), bd("-2"), 20);
    }

    public AnalyticsResponse analytics(Long settingId, Instant from, Instant to, BigDecimal targetRate,
            BigDecimal stopRate, int minimumSampleSize) {
        if (from == null || to == null || from.isAfter(to)
                || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(93)) > 0
                || minimumSampleSize < 1 || minimumSampleSize > 10000
                || targetRate == null || targetRate.signum() <= 0 || stopRate == null || stopRate.signum() >= 0)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "Analytics requires an ordered range of at most 93 days, positive target, negative stop and valid sample size");
        return cache.get(new AnalyticsKey(settingId, from, to, targetRate.stripTrailingZeros(),
                stopRate.stripTrailingZeros(), minimumSampleSize),
                () -> calculate(settingId, from, to, targetRate, stopRate, minimumSampleSize));
    }

    private record AnalyticsKey(Long settingId, Instant from, Instant to, BigDecimal targetRate,
            BigDecimal stopRate, int minimumSampleSize) {}

    private AnalyticsResponse calculate(Long settingId, Instant from, Instant to, BigDecimal targetRate,
            BigDecimal stopRate, int minimumSampleSize) {
        var rows = performances.findAnalyticsRows(settingId, from, to);
        long completed = rows.stream().filter(performance -> performance.getStatus() == PerformanceStatus.COMPLETED)
                .count();
        long missing = rows.stream().filter(performance -> performance.getStatus() == PerformanceStatus.DATA_MISSING)
                .count();
        String version = rows.stream()
                .map(AnalyticsRow::getCalculationVersion)
                .distinct()
                .reduce((left, right) -> "mixed")
                .orElse("performance-v2");

        return new AnalyticsResponse(
                rows.size(),
                completed,
                missing,
                win(rows, AnalyticsRow::getReturn5m),
                win(rows, AnalyticsRow::getReturnClose),
                avg(rows, AnalyticsRow::getReturn5m),
                avg(rows, AnalyticsRow::getReturn10m),
                avg(rows, AnalyticsRow::getReturn30m),
                avg(rows, AnalyticsRow::getReturn60m),
                avg(rows, AnalyticsRow::getReturnClose),
                version,
                targetRate,
                stopRate,
                targetStop(rows, targetRate, stopRate),
                timeBuckets(rows),
                signalCombinations(rows, minimumSampleSize),
                historicalEdge(rows, minimumSampleSize),
                minimumSampleSize);
    }

    private TargetStopSummary targetStop(List<AnalyticsRow> rows, BigDecimal targetRate, BigDecimal stopRate) {
        long targetFirst = 0;
        long stopFirst = 0;
        long neither = 0;
        for (AnalyticsRow row : rows) {
            boolean hitTarget = row.getMaxReturn() != null && row.getMaxReturn().compareTo(targetRate) >= 0;
            boolean hitStop = row.getMaxDrawdown() != null && row.getMaxDrawdown().compareTo(stopRate) <= 0;
            if (hitTarget && !hitStop)
                targetFirst++;
            else if (hitStop && !hitTarget)
                stopFirst++;
            else
                neither++;
        }
        return new TargetStopSummary(
                rows.size(),
                targetFirst,
                stopFirst,
                neither,
                rate(targetFirst, rows.size()),
                rate(stopFirst, rows.size()),
                avg(rows, AnalyticsRow::getReturnClose));
    }

    private List<TimeBucketResponse> timeBuckets(List<AnalyticsRow> rows) {
        Map<Integer, List<AnalyticsRow>> buckets = new LinkedHashMap<>();
        for (AnalyticsRow row : rows) {
            int hour = row.getDetectedAt().atZone(MARKET_ZONE).getHour();
            buckets.computeIfAbsent(hour, ignored -> new ArrayList<>()).add(row);
        }
        return buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new TimeBucketResponse(
                        String.format("%02d:00", entry.getKey()),
                        entry.getValue().size(),
                        win(entry.getValue(), AnalyticsRow::getReturnClose),
                        avg(entry.getValue(), AnalyticsRow::getReturnClose),
                        avg(entry.getValue(), AnalyticsRow::getMaxReturn),
                        avg(entry.getValue(), AnalyticsRow::getMaxDrawdown)))
                .toList();
    }

    private List<SignalCombinationResponse> signalCombinations(List<AnalyticsRow> rows, int minimumSampleSize) {
        Map<String, List<AnalyticsRow>> buckets = new LinkedHashMap<>();
        for (AnalyticsRow row : rows) {
            AnalyticsRow detection = row;
            String key = detection.getType().name() + "|" + band(detection.getOpportunityScore()) + "|"
                    + band(detection.getRiskScore());
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return buckets.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|");
                    List<AnalyticsRow> values = entry.getValue();
                    return new SignalCombinationResponse(
                            parts[0],
                            parts[1],
                            parts[2],
                            values.size(),
                            win(values, AnalyticsRow::getReturnClose),
                            avg(values, AnalyticsRow::getReturnClose),
                            avg(values, AnalyticsRow::getMaxReturn),
                            avg(values, AnalyticsRow::getMaxDrawdown),
                            confidence(values.size(), minimumSampleSize));
                })
                .sorted(Comparator.comparing(SignalCombinationResponse::confidence)
                        .thenComparing(SignalCombinationResponse::averageReturn,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private HistoricalEdgeResponse historicalEdge(List<AnalyticsRow> rows, int minimumSampleSize) {
        return new HistoricalEdgeResponse(
                rows.size(),
                win(rows, AnalyticsRow::getReturnClose),
                avg(rows, AnalyticsRow::getReturnClose),
                avg(rows, AnalyticsRow::getReturnClose),
                avg(rows, AnalyticsRow::getMfe),
                avg(rows, AnalyticsRow::getMae),
                confidence(rows.size(), minimumSampleSize),
                rows.size() >= minimumSampleSize);
    }

    private String band(BigDecimal value) {
        if (value == null)
            return "UNKNOWN";
        if (value.compareTo(bd("70")) >= 0)
            return "HIGH";
        if (value.compareTo(bd("40")) >= 0)
            return "MID";
        return "LOW";
    }

    private String confidence(long sampleSize, int minimumSampleSize) {
        if (sampleSize >= minimumSampleSize)
            return "HIGH";
        if (sampleSize >= Math.max(5, minimumSampleSize / 2))
            return "MEDIUM";
        return "LOW";
    }

    private BigDecimal avg(List<AnalyticsRow> rows, Function<AnalyticsRow, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal win(List<AnalyticsRow> rows, Function<AnalyticsRow, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        long wins = values.stream().filter(value -> value.signum() > 0).count();
        return rate(wins, values.size());
    }

    private BigDecimal rate(long count, long total) {
        if (total == 0)
            return null;
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
