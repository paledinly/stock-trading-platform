/*
 * 확정된 5분봉이 들어올 때마다 활성 스캐너 설정으로 평가하고, 조건에 새로 들어온 종목을 감지 기록으로 남기는 핵심 엔진이에요.
 * 중복 알림을 막는 쿨다운, 성과 추적 시작, 랭킹 반영, 이벤트 발행까지 한 번에 처리해요.
 * MarketDataService가 5분봉 확정 때 evaluate를, 미확정 갱신 때 observeIncomplete를 불러요.
 */
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 5분봉마다 조건에 맞는 종목을 찾아 감지 기록을 만드는 클래스예요.
 * 화재 감지기처럼 연기(조건)가 처음 감지될 때 한 번만 울리고, 연기가 사라졌다가(2번 연속 미충족) 다시 나야 또 울려요.
 * 너무 자주 울리지 않도록 쿨다운(다시 알리기 전에 쉬는 시간)도 걸어요. 실주문은 하지 않아요.
 */
@Service
public class ScannerEngine {
    // 현재는 사용자 1명(id 1)만 쓰는 구조라 소유자를 고정해 둬요.
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
    // 설정+종목별 연속 미충족 횟수예요. 2번 쌓이면 '조건 밖으로 나갔다'고 봐요.
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();
    // 지금 조건 안에 들어와 있는 설정+종목 상태예요. 이미 안에 있으면 다시 감지하지 않아요.
    private final Set<String> inside = ConcurrentHashMap.newKeySet();
    // Redis(빠른 메모리 저장소)를 못 쓸 때 쓰는, 이 서버 안의 쿨다운 만료 시각이에요.
    // 만료된 항목은 조회할 때 지우고, 항목이 LOCAL_COOLDOWN_SWEEP_THRESHOLD개를 넘으면 만료된 것을 한꺼번에 쓸어 내요.
    private final Map<String, Instant> localCooldowns = new ConcurrentHashMap<>();
    static final int LOCAL_COOLDOWN_SWEEP_THRESHOLD = 1000;

    /**
     * 하는 일: 필요한 저장소와 도구를 받아 보관해요.
     * 받는 것: 설정·감지·종목·캔들 레포지토리(DB 조회 담당), 시세 상태, 평가기, 이벤트 발행기,
     * Redis(없을 수도 있어서 ObjectProvider로 받아요), 성과 추적기, 진단 카운터, 특징값 엔진, JSON 변환기, 기회/위험 점수기.
     * 돌려주는 것: 없음(생성자). Redis가 없으면 redis 필드는 null이에요.
     */
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

    /**
     * 하는 일: 5분봉 하나를 모든 활성 설정으로 평가하고, 새로 조건에 들어온 종목이면 감지 기록을 저장하고 알려요.
     * 받는 것: current(5분봉 스냅샷).
     * 돌려주는 것: 없음. 아직 확정되지 않은 봉이면 나가기 판정만 하고 끝내요.
     */
    @Transactional
    public void evaluate(CandleSnapshot current) {
        if (!current.finalCandle()) { observeIncomplete(current); return; }
        diagnostics.scannerEvaluated();
        // 준비: 관리종목·거래정지 종목은 감지 대상에서 빼요.
        Stock stock = stocks.findByStockCodeAndActiveTrue(current.stockCode()).orElse(null);
        if (stock == null || stock.isManaged() || stock.isTradingHalted())
            return;
        // 바로 앞 5분봉 6개(약 30분)를 기준선으로 지표를 계산하고, 당일 누적 거래대금과 특징값도 준비해요.
        var previous = candles.findTop6ByStockIdAndTimeframeAndStartTimeBeforeOrderByStartTimeDesc(
                stock.getId(), "5M", current.startTime());
        var metrics = evaluator.calculate(current, previous);
        BigDecimal daily = quotes.get(current.stockCode()).map(t -> t.cumulativeTradingValue()).orElse(null);
        Optional<MarketFeatureSnapshot> feature = features.at(current.stockCode(), current.startTime());
        for (ScannerSetting setting : settings.findByOwnerIdAndActiveTrue(OWNER)) {
            // 처리: 설정마다 따로 판정해요. ETF/ETN은 설정에서 포함을 켠 경우에만 평가해요.
            String state = setting.getId() + ":" + current.stockCode();
            ScannerEvaluator.Decision decision = (!stock.isEtf() && !stock.isEtn() || setting.isIncludeEtf())
                    ? evaluator.evaluate(setting, metrics, current, daily, feature.orElse(null))
                    : new ScannerEvaluator.Decision(false, metrics.score(), "{\"state\":\"EXCLUDED_PRODUCT\"}");
            // 미충족이면 연속 2번째에 '조건 밖'으로 돌려놓아, 다음에 다시 들어올 때 새로 감지되게 해요.
            if (!decision.matched()) {
                if (inside.contains(state) && misses.merge(state, 1, Integer::sum) >= 2) {
                    inside.remove(state);
                    misses.remove(state);
                }
                continue;
            }
            // 충족: 미충족 횟수를 지우고, 이미 조건 안에 있던 종목이면 중복 감지하지 않아요.
            misses.remove(state);
            if (!inside.add(state))
                continue;
            UUID eventId = UUID.randomUUID();
            // 쿨다운을 못 잡으면(최근에 이미 알림) 상태를 되돌리고 넘어가요.
            if (!acquire(setting, current.stockCode(), eventId)) {
                inside.remove(state);
                continue;
            }
            String settingSnapshot = snapshot(setting);
            // 감지 시각은 봉 시작 + 5분, 즉 봉이 확정되는 시각으로 기록해요.
            var detectedAt = current.startTime().plus(Duration.ofMinutes(5));
            ScannerDetection detection = new ScannerDetection(eventId, stock, setting, detectedAt, current.close(),
                    metrics.changeRate(), metrics.volumeRatio(), current.volume(), current.tradingValue(), daily,
                    decision.score(), settingSnapshot, "candle:" + current.startTime());
            detection.recordReason(decision.reasonJson());
            if (feature.isPresent())
                attachFeature(detection, feature.get());
            attachScore(detection, scorer.score(metrics, feature.orElse(null)));
            // 결과: 저장 → 성과 추적 시작 → 랭킹 반영 → 이벤트 발행 순서로 마무리해요.
            detection = detections.save(detection);
            tracker.initialize(detection);
            diagnostics.detectionCreated();
            rank(setting, stock, decision.score());
            events.publish("scanner." + eventName(setting.getType()) + ".detected",
                    detectionPayload(detection, eventId, stock, current, metrics, decision));
        }
    }

    /**
     * Preserve incomplete-window exit bookkeeping without querying rows that cannot qualify.
     *
     * 하는 일: 아직 확정되지 않은 봉이 오면, 그 종목에 걸린 '조건 안' 상태들의 미충족 횟수만 올려요(DB 조회 없음).
     * 받는 것: current(미확정 5분봉).
     * 돌려주는 것: 없음.
     * ⚠️ 확인 필요: 미확정 봉 갱신(candle.5m.updated)이 한 5분 구간에 여러 번 오면 확정봉 평가 없이도 2회가 쌓여
     * '조건 밖'으로 바뀔 수 있어요. 확정봉 기준 '2번 연속 미충족' 의도와 다를 수 있어요.
     */
    public void observeIncomplete(CandleSnapshot current) {
        diagnostics.scannerEvaluated();
        String suffix = ":" + current.stockCode();
        for (String state : inside) {
            if (state.endsWith(suffix) && misses.merge(state, 1, Integer::sum) >= 2) {
                inside.remove(state); misses.remove(state);
            }
        }
    }

    /**
     * 하는 일: 감지 이벤트로 내보낼 내용(payload)을 만들어요.
     * 받는 것: detection(저장된 감지), eventId, stock, current(현재 봉), metrics(지표), decision(판정).
     * 돌려주는 것: 순서가 유지되는 키-값 맵. 숫자는 문자열로, 없는 값은 빈 문자열로 넣어요.
     */
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

    /**
     * 하는 일: 기회/위험 점수와 세부 내역(JSON)을 감지 기록에 붙여요.
     * 받는 것: detection, score(점수 결과).
     * 돌려주는 것: 없음. JSON 변환이 실패하면 점수 없이 진단 오류만 남겨요.
     */
    private void attachScore(ScannerDetection detection, OpportunityRiskScore score) {
        try {
            detection.attachOpportunityRisk(score.opportunityScore(), score.riskScore(), score.scoreVersion(),
                    objectMapper.writeValueAsString(score));
        } catch (JsonProcessingException error) {
            diagnostics.error("Opportunity/risk score serialization failed: " + error.getMessage());
        }
    }

    /**
     * 하는 일: 감지 당시 특징값(VWAP·체결강도 등) 스냅샷을 JSON으로 붙여요.
     * 받는 것: detection, feature(특징값 스냅샷).
     * 돌려주는 것: 없음. 변환이 실패하면 진단 오류만 남겨요.
     */
    private void attachFeature(ScannerDetection detection, MarketFeatureSnapshot feature) {
        try {
            detection.attachFeatureSnapshot(feature.featureVersion(), objectMapper.writeValueAsString(feature));
        } catch (JsonProcessingException error) {
            diagnostics.error("Feature snapshot serialization failed: " + error.getMessage());
        }
    }

    /**
     * 하는 일: Redis 정렬 집합에 종목 점수를 넣어 설정별 랭킹(점수 순위표)을 10분 동안 유지해요.
     * 받는 것: setting, stock, score(점수, null이면 건너뛰어요).
     * 돌려주는 것: 없음. 실패해도 감지 흐름은 멈추지 않아요.
     */
    void rank(ScannerSetting setting, Stock stock, BigDecimal score) {
        // Redis가 없으면 랭킹을 둘 곳이 없으니 바로 끝내요(Redis 없음은 쿨다운 대체 진단에 이미 남아요).
        if (score == null || redis == null)
            return;
        try {
            String key = "scanner:" + setting.getType() + ":rank:" + setting.getId();
            redis.opsForZSet().add(key, stock.getStockCode(), score.doubleValue());
            redis.expire(key, Duration.ofMinutes(10));
        } catch (RuntimeException error) {
            // Redis 오류로 랭킹이 빠지면 감지 흐름은 계속하되, 그 횟수를 진단(scannerRankFailures)에 남겨요.
            diagnostics.scannerRankFailed(error.getClass().getSimpleName());
        }
    }

    /**
     * Redis cooldown is the normal path. When Redis is missing or fails, a process-local cooldown keeps
     * detections flowing instead of silently dropping every detection (2026-10-12 had none all day).
     *
     * 하는 일: 설정+종목 쿨다운을 잡아요. 잡으면 true(알림 가능), 이미 쿨다운 중이면 false예요.
     * 받는 것: setting, code(종목코드), event(이번 감지 id).
     * Redis setIfAbsent(키가 없을 때만 저장)가 기본 경로이고, 실패하면 이 서버 메모리로 대신해요.
     */
    boolean acquire(ScannerSetting setting, String code, UUID event) {
        return acquire(setting, code, event, Instant.now());
    }

    /**
     * 하는 일: acquire 본체예요(기준 시각을 받을 수 있어 테스트하기 쉬워요). 메모리 대체 경로에서만 now를 써요.
     * 받는 것: setting, code(종목코드), event(이번 감지 id), now(기준 시각).
     * 돌려주는 것: 쿨다운을 잡으면 true, 이미 쿨다운 중이면 false.
     */
    boolean acquire(ScannerSetting setting, String code, UUID event, Instant now) {
        String key = "scanner:" + setting.getType() + ":cooldown:" + setting.getId() + ":" + code;
        Duration cooldown = Duration.ofSeconds(setting.getCooldownSeconds());
        if (redis != null) {
            try {
                Boolean ok = redis.opsForValue().setIfAbsent(key, event.toString(), cooldown);
                if (ok != null)
                    return ok;
                diagnostics.scannerCooldownFallback("Redis cooldown returned no result");
            } catch (RuntimeException error) {
                diagnostics.scannerCooldownFallback("Redis cooldown failed: " + error.getClass().getSimpleName());
            }
        } else {
            diagnostics.scannerCooldownFallback("Redis unavailable");
        }
        // 대체 경로: 서버 메모리에 '언제까지 쉬는지'를 적어 같은 효과를 내요. 서버가 여러 대면 서로 공유되지 않아요.
        // 만료된 항목은 '없는 것'과 같아서, 지워도 쿨다운 판단은 달라지지 않아요.
        Instant previous = localCooldowns.get(key);
        if (previous != null && now.isBefore(previous))
            return false;
        if (localCooldowns.size() > LOCAL_COOLDOWN_SWEEP_THRESHOLD)
            localCooldowns.values().removeIf(until -> !now.isBefore(until));
        localCooldowns.put(key, now.plus(cooldown));
        return true;
    }

    /** 하는 일: 메모리 쿨다운 항목 수를 돌려줘요(테스트·진단용). */
    int localCooldownCount() {
        return localCooldowns.size();
    }

    /**
     * 하는 일: 스캐너 종류를 이벤트 이름 조각으로 바꿔요.
     * 받는 것: type(스캐너 종류).
     * 돌려주는 것: 이름 조각(예: volume-breakout). 이벤트 이름은 scanner.<조각>.detected 형태가 돼요.
     */
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

    /**
     * 하는 일: 감지 당시 설정 값을 JSON 문자열로 찍어 둬요. 나중에 설정이 바뀌어도 그때 기준을 알 수 있어요.
     * 받는 것: s(설정).
     * 돌려주는 것: JSON 문자열. 최소 가격과 ETF 포함 여부는 들어가지 않아요.
     */
    private String snapshot(ScannerSetting s) {
        return "{\"algorithmVersion\":\"momentum-v1\",\"type\":\"" + s.getType() + "\",\"minChangeRate\":\""
                + s.getMinChangeRate() + "\",\"minVolumeRatio\":\"" + s.getMinVolumeRatio()
                + "\",\"min5mTradingValue\":\"" + s.getMinFiveMinuteTradingValue()
                + "\",\"minDailyTradingValue\":\"" + s.getMinDailyTradingValue()
                + "\",\"cooldownSeconds\":" + s.getCooldownSeconds() + "}";
    }
}
