package com.sunmo.stockplatform.scanner.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import com.sunmo.stockplatform.candle.application.CandleSnapshot;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.domain.*;
import com.sunmo.stockplatform.scanner.infrastructure.*;
import com.sunmo.stockplatform.scanner.score.*;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ScannerEngine {
    private static final long OWNER = 1L;
    private final ScannerSettingRepository settings;
    private final ScannerDetectionRepository detections;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final QuoteStateStore quotes;
    private final ScannerEvaluator evaluator;
    private final MarketEventGateway events;
    private final StringRedisTemplate redis;
    private final DetectionPerformanceTracker tracker;
    private final RealtimeDiagnostics diagnostics;
    private final MarketFeatureEngine features;
    private final ObjectMapper objectMapper;
    private final OpportunityRiskScorer scorer;
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();
    private final Set<String> inside = ConcurrentHashMap.newKeySet();

    public ScannerEngine(ScannerSettingRepository settings, ScannerDetectionRepository detections,
            StockRepository stocks, StockCandleRepository candles, QuoteStateStore quotes,
            ScannerEvaluator evaluator, MarketEventGateway events,
            ObjectProvider<StringRedisTemplate> redis, DetectionPerformanceTracker tracker,
            RealtimeDiagnostics diagnostics, MarketFeatureEngine features, ObjectMapper objectMapper,
            OpportunityRiskScorer scorer) {
        this.settings = settings;
        this.detections = detections;
        this.stocks = stocks;
        this.candles = candles;
        this.quotes = quotes;
        this.evaluator = evaluator;
        this.events = events;
        this.redis = redis.getIfAvailable();
        this.tracker = tracker;
        this.diagnostics = diagnostics;
        this.features = features;
        this.objectMapper = objectMapper;
        this.scorer = scorer;
    }

    @Transactional
    public void evaluate(CandleSnapshot current) {
        diagnostics.scannerEvaluated();
        Stock stock = stocks.findByStockCodeAndActiveTrue(current.stockCode()).orElse(null);
        if (stock == null || stock.isManaged() || stock.isTradingHalted())
            return;
        var previous = candles.findTop6ByStockIdAndTimeframeAndStartTimeBeforeOrderByStartTimeDesc(
                stock.getId(), "5M", current.startTime());
        var metrics = evaluator.calculate(current, previous);
        BigDecimal daily = quotes.get(current.stockCode()).map(t -> t.cumulativeTradingValue()).orElse(null);
        Optional<MarketFeatureSnapshot> feature = features.latest(current.stockCode());
        for (ScannerSetting setting : settings.findByOwnerIdAndActiveTrue(OWNER)) {
            String state = setting.getId() + ":" + current.stockCode();
            ScannerEvaluator.Decision decision = (!stock.isEtf() && !stock.isEtn() || setting.isIncludeEtf())
                    ? evaluator.evaluate(setting, metrics, current, daily, feature.orElse(null))
                    : new ScannerEvaluator.Decision(false, metrics.score(), "{\"state\":\"EXCLUDED_PRODUCT\"}");
            if (!decision.matched()) {
                if (inside.contains(state) && misses.merge(state, 1, Integer::sum) >= 2) {
                    inside.remove(state);
                    misses.remove(state);
                }
                continue;
            }
            misses.remove(state);
            if (!inside.add(state))
                continue;
            UUID eventId = UUID.randomUUID();
            if (!acquire(setting, current.stockCode(), eventId)) {
                inside.remove(state);
                continue;
            }
            String settingSnapshot = snapshot(setting);
            var detectedAt = java.time.Instant.now();
            ScannerDetection detection = new ScannerDetection(eventId, stock, setting, detectedAt, current.close(),
                    metrics.changeRate(), metrics.volumeRatio(), current.volume(), current.tradingValue(), daily,
                    decision.score(), settingSnapshot, "candle:" + current.startTime());
            detection.recordReason(decision.reasonJson());
            if (feature.isPresent())
                attachFeature(detection, feature.get());
            attachScore(detection, scorer.score(metrics, feature.orElse(null)));
            detection = detections.save(detection);
            tracker.initialize(detection);
            diagnostics.detectionCreated();
            rank(setting, stock, decision.score());
            events.publish("scanner." + eventName(setting.getType()) + ".detected",
                    detectionPayload(detection, eventId, stock, current, metrics, decision));
        }
    }

    private Map<String, Object> detectionPayload(ScannerDetection detection, UUID eventId, Stock stock,
            CandleSnapshot current, ScannerEvaluator.Metrics metrics,
            ScannerEvaluator.Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("detectionId", detection.getId());
        payload.put("eventId", eventId.toString());
        payload.put("stockCode", stock.getStockCode());
        payload.put("stockName", stock.getStockName());
        payload.put("price", current.close().toPlainString());
        payload.put("changeRate", metrics.changeRate().toPlainString());
        payload.put("volumeRatio", metrics.volumeRatio().toPlainString());
        payload.put("score", decision.score() == null ? "" : decision.score().toPlainString());
        payload.put("opportunityScore",
                detection.getOpportunityScore() == null ? "" : detection.getOpportunityScore().toPlainString());
        payload.put("riskScore", detection.getRiskScore() == null ? "" : detection.getRiskScore().toPlainString());
        payload.put("reason", decision.reasonJson());
        payload.put("featureVersion", detection.getFeatureVersion() == null ? "" : detection.getFeatureVersion());
        return payload;
    }

    private void attachScore(ScannerDetection detection, OpportunityRiskScore score) {
        try {
            detection.attachOpportunityRisk(score.opportunityScore(), score.riskScore(), score.scoreVersion(),
                    objectMapper.writeValueAsString(score));
        } catch (JsonProcessingException error) {
            diagnostics.error("Opportunity/risk score serialization failed: " + error.getMessage());
        }
    }

    private void attachFeature(ScannerDetection detection, MarketFeatureSnapshot feature) {
        try {
            detection.attachFeatureSnapshot(feature.featureVersion(), objectMapper.writeValueAsString(feature));
        } catch (JsonProcessingException error) {
            diagnostics.error("Feature snapshot serialization failed: " + error.getMessage());
        }
    }

    private void rank(ScannerSetting setting, Stock stock, BigDecimal score) {
        if (score == null)
            return;
        try {
            String key = "scanner:" + setting.getType() + ":rank:" + setting.getId();
            redis.opsForZSet().add(key, stock.getStockCode(), score.doubleValue());
            redis.expire(key, Duration.ofMinutes(10));
        } catch (RuntimeException ignored) {
        }
    }

    private boolean acquire(ScannerSetting setting, String code, UUID event) {
        if (redis == null)
            return false;
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    "scanner:" + setting.getType() + ":cooldown:" + setting.getId() + ":" + code,
                    event.toString(), Duration.ofSeconds(setting.getCooldownSeconds()));
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private String eventName(ScannerType type) {
        return switch (type) {
            case VOLUME -> "volume";
            case PRICE_RISE -> "price";
            case MOMENTUM -> "momentum";
            case VOLUME_BREAKOUT -> "volume-breakout";
            case TURNOVER_BREAKOUT -> "turnover-breakout";
            case HIGH_BREAKOUT -> "high-breakout";
            case VWAP_BREAKOUT -> "vwap-breakout";
            case VWAP_RECLAIM -> "vwap-reclaim";
            case PULLBACK_REBREAK -> "pullback-rebreak";
        };
    }

    private String snapshot(ScannerSetting s) {
        return "{\"algorithmVersion\":\"momentum-v1\",\"type\":\"" + s.getType() + "\",\"minChangeRate\":\""
                + s.getMinChangeRate() + "\",\"minVolumeRatio\":\"" + s.getMinVolumeRatio()
                + "\",\"min5mTradingValue\":\"" + s.getMinFiveMinuteTradingValue()
                + "\",\"minDailyTradingValue\":\"" + s.getMinDailyTradingValue()
                + "\",\"cooldownSeconds\":" + s.getCooldownSeconds() + "}";
    }
}
