package com.sunmo.stockplatform.analytics.application;

import com.sunmo.stockplatform.analytics.api.PerformanceDtos.AnalyticsResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.HistoricalEdgeResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.PerformanceResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.SignalCombinationResponse;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.TargetStopSummary;
import com.sunmo.stockplatform.analytics.api.PerformanceDtos.TimeBucketResponse;
import com.sunmo.stockplatform.analytics.domain.DetectionPerformance;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
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

    public ScannerAnalyticsService(ScannerDetectionRepository detections,
            DetectionPerformanceRepository performances) {
        this.detections = detections;
        this.performances = performances;
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
        var rows = detections.findByDetectedAtBetweenOrderByDetectedAtAsc(from, to).stream()
                .filter(detection -> settingId == null || detection.getSetting().getId().equals(settingId))
                .map(detection -> performances.findById(detection.getId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        long completed = rows.stream().filter(performance -> performance.getStatus() == PerformanceStatus.COMPLETED)
                .count();
        long missing = rows.stream().filter(performance -> performance.getStatus() == PerformanceStatus.DATA_MISSING)
                .count();
        String version = rows.stream()
                .map(DetectionPerformance::getCalculationVersion)
                .distinct()
                .reduce((left, right) -> "mixed")
                .orElse("performance-v2");

        return new AnalyticsResponse(
                rows.size(),
                completed,
                missing,
                win(rows, DetectionPerformance::getReturn5m),
                win(rows, DetectionPerformance::getReturnClose),
                avg(rows, DetectionPerformance::getReturn5m),
                avg(rows, DetectionPerformance::getReturn10m),
                avg(rows, DetectionPerformance::getReturn30m),
                avg(rows, DetectionPerformance::getReturn60m),
                avg(rows, DetectionPerformance::getReturnClose),
                version,
                targetRate,
                stopRate,
                targetStop(rows, targetRate, stopRate),
                timeBuckets(rows),
                signalCombinations(rows, minimumSampleSize),
                historicalEdge(rows, minimumSampleSize),
                minimumSampleSize);
    }

    private TargetStopSummary targetStop(List<DetectionPerformance> rows, BigDecimal targetRate, BigDecimal stopRate) {
        long targetFirst = 0;
        long stopFirst = 0;
        long neither = 0;
        for (DetectionPerformance row : rows) {
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
                avg(rows, DetectionPerformance::getReturnClose));
    }

    private List<TimeBucketResponse> timeBuckets(List<DetectionPerformance> rows) {
        Map<Integer, List<DetectionPerformance>> buckets = new LinkedHashMap<>();
        for (DetectionPerformance row : rows) {
            int hour = row.getDetection().getDetectedAt().atZone(MARKET_ZONE).getHour();
            buckets.computeIfAbsent(hour, ignored -> new ArrayList<>()).add(row);
        }
        return buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new TimeBucketResponse(
                        String.format("%02d:00", entry.getKey()),
                        entry.getValue().size(),
                        win(entry.getValue(), DetectionPerformance::getReturnClose),
                        avg(entry.getValue(), DetectionPerformance::getReturnClose),
                        avg(entry.getValue(), DetectionPerformance::getMaxReturn),
                        avg(entry.getValue(), DetectionPerformance::getMaxDrawdown)))
                .toList();
    }

    private List<SignalCombinationResponse> signalCombinations(List<DetectionPerformance> rows, int minimumSampleSize) {
        Map<String, List<DetectionPerformance>> buckets = new LinkedHashMap<>();
        for (DetectionPerformance row : rows) {
            ScannerDetection detection = row.getDetection();
            String key = detection.getType().name() + "|" + band(detection.getOpportunityScore()) + "|"
                    + band(detection.getRiskScore());
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return buckets.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|");
                    List<DetectionPerformance> values = entry.getValue();
                    return new SignalCombinationResponse(
                            parts[0],
                            parts[1],
                            parts[2],
                            values.size(),
                            win(values, DetectionPerformance::getReturnClose),
                            avg(values, DetectionPerformance::getReturnClose),
                            avg(values, DetectionPerformance::getMaxReturn),
                            avg(values, DetectionPerformance::getMaxDrawdown),
                            confidence(values.size(), minimumSampleSize));
                })
                .sorted(Comparator.comparing(SignalCombinationResponse::confidence)
                        .thenComparing(SignalCombinationResponse::averageReturn,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private HistoricalEdgeResponse historicalEdge(List<DetectionPerformance> rows, int minimumSampleSize) {
        return new HistoricalEdgeResponse(
                rows.size(),
                win(rows, DetectionPerformance::getReturnClose),
                avg(rows, DetectionPerformance::getReturnClose),
                avg(rows, DetectionPerformance::getReturnClose),
                avg(rows, DetectionPerformance::getMfe),
                avg(rows, DetectionPerformance::getMae),
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

    private BigDecimal avg(List<DetectionPerformance> rows, Function<DetectionPerformance, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal win(List<DetectionPerformance> rows, Function<DetectionPerformance, BigDecimal> getter) {
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
