package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ClosingPrecisionEvaluator {
    public static final int LIMITED_MODE_MAX_CANDIDATES = 1;
    private static final Duration MAX_SIGNAL_AGE = Duration.ofMinutes(30);
    private static final Duration MAX_CANDLE_AGE = Duration.ofMinutes(10);
    public static final BigDecimal MIN_DAILY_VALUE = new BigDecimal("1000000000");
    public static final BigDecimal MIN_FIVE_MINUTE_VALUE = new BigDecimal("20000000");
    public static final BigDecimal MAX_MA20_DISTANCE_PERCENT = new BigDecimal("12");
    private static final BigDecimal MIN_SIGNAL_RETENTION = new BigDecimal("0.99");
    private final ClosingRecommendationScorer scorer;
    private final IntradayMovingAverageService intradayMa;
    private final DailyMovingAverageService dailyMa;
    private final StockCandleRepository candles;
    private final ClosingRecommendationProperties properties;
    private final ObjectMapper mapper;
    private final ClosingTradingCalendar calendar;

    public ClosingPrecisionEvaluator(ClosingRecommendationScorer scorer, IntradayMovingAverageService intradayMa,
            DailyMovingAverageService dailyMa, StockCandleRepository candles,
            ClosingRecommendationProperties properties, ObjectMapper mapper, ClosingTradingCalendar calendar) {
        this.scorer = scorer;
        this.intradayMa = intradayMa;
        this.dailyMa = dailyMa;
        this.candles = candles;
        this.properties = properties;
        this.mapper = mapper;
        this.calendar = calendar;
    }

    public List<Assessment> representatives(List<ScannerDetection> source, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk) {
        Map<Long, Assessment> selected = new LinkedHashMap<>();
        source.stream().filter(row -> !row.getDetectedAt().isBefore(from) && !row.getDetectedAt().isAfter(asOf))
                .sorted(Comparator.comparing(ScannerDetection::getDetectedAt).reversed())
                .forEach(row -> selected.computeIfAbsent(row.getStock().getId(), ignored ->
                        assess(row, from, asOf, minOpportunity, maxRisk)));
        return List.copyOf(selected.values());
    }

    public List<Assessment> ranked(List<ScannerDetection> source, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk, int limit) {
        return representatives(source, from, asOf, minOpportunity, maxRisk).stream()
                .filter(row -> row.reason().equals("QUALIFIED"))
                .sorted(Comparator.comparing((Assessment row) -> row.score().score()).reversed()
                        .thenComparing(row -> row.detection().getDetectedAt(), Comparator.reverseOrder()))
                .limit(Math.min(limit, LIMITED_MODE_MAX_CANDIDATES)).toList();
    }

    public Assessment assess(ScannerDetection detection, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk) {
        List<String> missing = new ArrayList<>();
        if (detection.getOpportunityScore() == null) missing.add("opportunityScore");
        if (detection.getRiskScore() == null) missing.add("riskScore");
        if (detection.getVolumeRatio() == null) missing.add("volumeRatio");
        try {
            JsonNode node = mapper.readTree(detection.getFeatureSnapshot());
            for (String field : List.of("vwapDistanceRate", "dayHighDistanceRate", "tradeStrength")) {
                if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) missing.add(field);
            }
        } catch (Exception error) {
            missing.add("featureSnapshot");
        }
        if (Duration.between(detection.getDetectedAt(), asOf).compareTo(MAX_SIGNAL_AGE) > 0)
            missing.add("STALE_FEATURE");
        if (detection.getReceivedAt() != null && detection.getReceivedAt().isAfter(asOf))
            missing.add("RECEIVED_AFTER_EVALUATION");

        List<StockCandle> session = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        detection.getStock().getId(), "5M", from, asOf.plusSeconds(1));
        Set<Instant> starts = new HashSet<>();
        boolean lateRevision = false;
        StockCandle latest = null;
        for (StockCandle candle : session) {
            if (!candle.isFinalCandle() || candle.getStartTime().plus(Duration.ofMinutes(5)).isAfter(asOf))
                continue;
            if (candle.getUpdatedAt() != null && candle.getUpdatedAt().isAfter(asOf)) {
                lateRevision = true;
                continue;
            }
            if (latest == null || candle.getStartTime().isAfter(latest.getStartTime())) latest = candle;
            if (!candle.getStartTime().plus(Duration.ofMinutes(5)).isAfter(detection.getDetectedAt()))
                starts.add(candle.getStartTime());
        }
        Instant bucket = detection.getDetectedAt().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        bucket = bucket.minusSeconds(bucket.atZone(java.time.ZoneId.of("Asia/Seoul")).getMinute() % 5 * 60L);
        int finalCandles = 0;
        for (Instant expected = bucket.minus(Duration.ofMinutes(5)); starts.contains(expected);
                expected = expected.minus(Duration.ofMinutes(5))) finalCandles++;
        if (lateRevision) missing.add("RECEIVED_AFTER_EVALUATION");
        if (finalCandles < properties.minimumFinalCandles()) missing.add("CANDLE_GAP");

        IntradayMovingAverageFeature intraday = intradayMa.calculate(detection);
        DailyMovingAverageFeature daily = dailyMa.calculate(detection);
        if (daily == null || daily.candleCount() < 21 || !daily.ready()) missing.add("DAILY_DATA_MISSING");
        if (daily != null && daily.ready() && daily.asOfDate() != null
                && !daily.asOfDate().equals(calendar.previousTradingDay(detection.getSessionDate())))
            missing.add("DAILY_DATA_STALE");
        boolean dailyTrend = daily != null && daily.ready() && daily.ma20() != null
                && daily.closeAboveMa20() && daily.ma20Rising();
        boolean latestFresh = latest != null && !latest.getStartTime().plus(Duration.ofMinutes(5))
                .isBefore(asOf.minus(MAX_CANDLE_AGE));
        boolean retained = latest != null && latest.getClose() != null && detection.getDetectedPrice() != null
                && latest.getClose().compareTo(detection.getDetectedPrice().multiply(MIN_SIGNAL_RETENTION)) >= 0;
        boolean overextended = daily != null && daily.ma20() != null && detection.getDetectedPrice() != null
                && detection.getDetectedPrice().compareTo(daily.ma20().multiply(BigDecimal.ONE
                        .add(MAX_MA20_DISTANCE_PERCENT.movePointLeft(2)))) > 0;
        boolean liquid = detection.getDailyValue() != null && latest != null && latest.getTradingValue() != null
                && detection.getDailyValue().compareTo(MIN_DAILY_VALUE) >= 0
                && latest.getTradingValue().compareTo(MIN_FIVE_MINUTE_VALUE) >= 0;
        ScoreResult score = scorer.score(detection,
                intraday == null ? IntradayMovingAverageFeature.empty(0) : intraday,
                daily == null ? DailyMovingAverageFeature.empty(0) : daily);
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("detectedAt", detection.getDetectedAt().toString());
        readiness.put("receivedAt", detection.getReceivedAt() == null ? null : detection.getReceivedAt().toString());
        readiness.put("evaluatedAsOf", asOf.toString());
        readiness.put("receiptVerified", detection.getReceivedAt() != null);
        readiness.put("lastFinalCandleAt", latest == null ? null : latest.getStartTime().toString());
        readiness.put("finalCandles", finalCandles);
        readiness.put("intradayMaCandles", intraday == null ? 0 : intraday.candleCount());
        readiness.put("dailyCandles", daily == null ? 0 : daily.candleCount());
        readiness.put("dailyAsOfDate", daily == null || daily.asOfDate() == null ? null : daily.asOfDate().toString());
        readiness.put("lateCandleRevision", lateRevision);
        readiness.put("featureVersion", detection.getFeatureVersion());
        readiness.put("dailyTrend", dailyTrend ? "PASS" : "FAIL");
        readiness.put("latestFiveMinute", latestFresh ? "PASS" : "FAIL");
        readiness.put("signalRetained", retained ? "PASS" : "FAIL");
        readiness.put("overextension", overextended ? "FAIL" : "PASS");
        readiness.put("liquidity", liquid ? "PASS" : "FAIL");
        readiness.put("latestFiveMinuteClose", latest == null || latest.getClose() == null ? null : latest.getClose().toPlainString());
        readiness.put("dailyTradingValue", detection.getDailyValue() == null ? null : detection.getDailyValue().toPlainString());
        readiness.put("latestFiveMinuteTradingValue", latest == null || latest.getTradingValue() == null
                ? null : latest.getTradingValue().toPlainString());
        readiness.put("marketRegime", "UNVERIFIED");
        readiness.put("sectorExposure", "UNVERIFIED");
        readiness.put("accountExposure", "UNVERIFIED");
        readiness.put("orderEligible", false);
        String reason;
        Stock stock = detection.getStock();
        if (!stock.isActive() || stock.isManaged() || stock.isTradingHalted() || stock.isEtf() || stock.isEtn())
            reason = "NOT_TRADABLE";
        else if (detection.getOpportunityScore() == null || detection.getRiskScore() == null
                || detection.getOpportunityScore().compareTo(minOpportunity) < 0
                || detection.getRiskScore().compareTo(maxRisk) > 0)
            reason = "OPPORTUNITY_OR_RISK_FILTERED";
        else if (!missing.isEmpty()) reason = missing.getFirst();
        else if (finalCandles * 5 < properties.minimumCoverageMinutes()) reason = "INSUFFICIENT_INTRADAY_COVERAGE";
        else if (!dailyTrend) reason = "DAILY_TREND_WEAK";
        else if (!latestFresh) reason = "LATEST_CANDLE_STALE";
        else if (!retained) reason = "INTRADAY_REVERSAL";
        else if (overextended) reason = "OVEREXTENDED";
        else if (!liquid) reason = "LOW_LIQUIDITY";
        else if (score.score().compareTo(properties.minimumFinalScore()) < 0) reason = "LOW_FINAL_SCORE";
        else reason = "QUALIFIED";
        return new Assessment(detection, score, finalCandles, finalCandles * 5, List.copyOf(missing), reason,
                java.util.Collections.unmodifiableMap(readiness));
    }

    public record Assessment(ScannerDetection detection, ScoreResult score, int finalCandles,
            int coverageMinutes, List<String> missingFeatures, String reason, Map<String, Object> dataReadiness) { }
}
