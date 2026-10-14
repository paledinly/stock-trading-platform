/*
 * 정밀(PRECISION) 후보(실시간 구독 종목의 확정 5분봉에서 Scanner가 잡은 탐지)를 종목별 대표 탐지 1건으로 줄이고,
 * 평가 시각까지 실제로 받아 둔 입력만으로 관문(통과해야 하는 조건)을 검사해 QUALIFIED 또는 탈락 사유를 정해요.
 * ClosingRecommendationService(실제 마감 평가)와 OvernightBacktestService(같은 규칙의 백테스트)가 써요.
 */
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

/**
 * 정밀 후보를 평가하는 심사원이에요. 입력 마감 이후 수신된 탐지·봉은 "그때는 없던 정보"로 보고 빼요(ADR-009).
 * 면접장 문이 닫힌 뒤 도착한 서류는 받지 않고, 문 닫기 전에 받은 서류만 보고 체크리스트를 위에서부터 차례로 확인해
 * 처음 걸린 항목을 탈락 사유로 적는 면접관이라고 생각하면 돼요. 점수는 상대 순위용이며 상승 확률이 아니에요.
 */
@Component
public class ClosingPrecisionEvaluator {
    // 최종 추천(SELECTED) 최대 종목 수예요. 요청 limit이 더 커도 5개를 넘지 않아요.
    public static final int LIMITED_MODE_MAX_CANDIDATES = 5;
    // 신호가 너무 오래되었거나(30분), 최신 확정봉이 너무 오래되었는지(10분) 보는 기준이에요.
    private static final Duration MAX_SIGNAL_AGE = Duration.ofMinutes(30);
    private static final Duration MAX_CANDLE_AGE = Duration.ofMinutes(10);
    // 일봉 20일 이동평균선 대비 최대 이격(12%)과, 탐지가 대비 현재 종가 최소 유지율(99%)이에요.
    public static final BigDecimal MAX_MA20_DISTANCE_PERCENT = new BigDecimal("12");
    private static final BigDecimal MIN_SIGNAL_RETENTION = new BigDecimal("0.99");
    private final ClosingRecommendationScorer scorer;
    private final IntradayMovingAverageService intradayMa;
    private final DailyMovingAverageService dailyMa;
    private final StockCandleRepository candles;
    private final ClosingRecommendationProperties properties;
    private final ObjectMapper mapper;
    private final ClosingTradingCalendar calendar;
    // 궤적(trajectory) 저장소는 선택 사항이에요. 있으면 기록만 하고 점수에는 쓰지 않아요(SHADOW).
    private com.sunmo.stockplatform.closing.trajectory.TrajectoryStore trajectory;

    /**
     * 궤적 저장소가 있으면 주입받아요.
     * 받는 것: 궤적 저장소. 돌려주는 것: 없음.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setTrajectory(com.sunmo.stockplatform.closing.trajectory.TrajectoryStore trajectory) { this.trajectory = trajectory; }

    /**
     * 점수기, 장중·일봉 이동평균 계산기, 5분봉 저장소, 마감 정책, JSON 변환기, 거래일 달력을 받아요.
     */
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

    /**
     * 이동평균 관문을 적용해 종목별 대표 탐지를 평가해요(운영 기본 경로).
     * 받는 것: 탐지 목록, 평가 시작 시각, 평가 기준 시각(asOf, 입력 마감), 최소 기회 점수, 최대 위험 점수.
     * 돌려주는 것: 종목별 평가 결과 목록.
     */
    public List<Assessment> representatives(List<ScannerDetection> source, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk) {
        return representatives(source, from, asOf, minOpportunity, maxRisk, true);
    }

    /**
     * 이동평균 관문 없이 대표 탐지를 평가해요(비교 실험용 경로로 보여요).
     * 받는 것·돌려주는 것: representatives와 같아요.
     */
    public List<Assessment> representativesWithoutMovingAverage(List<ScannerDetection> source, Instant from,
            Instant asOf, BigDecimal minOpportunity, BigDecimal maxRisk) {
        return representatives(source, from, asOf, minOpportunity, maxRisk, false);
    }

    /**
     * 탐지를 시간 구간과 수신 시각으로 거른 뒤, 종목마다 가장 늦은 탐지 1건만 평가해요.
     * 받는 것: 탐지 목록, 구간 시작, 기준 시각, 최소 기회·최대 위험 점수, 이동평균 관문 적용 여부.
     * 돌려주는 것: 종목별 평가 결과(가장 최근 탐지 순).
     */
    private List<Assessment> representatives(List<ScannerDetection> source, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk, boolean applyMovingAverage) {
        // 준비: 입력 수신 마감 = 기준 시각 + 봉 확정 유예(기본 0초).
        Map<Long, Assessment> selected = new LinkedHashMap<>();
        Instant availableBy = asOf.plus(properties.candleFinalizationGrace());
        // v9.1: a detection received after the input deadline did not exist at evaluation, so it is excluded
        // instead of hiding the stock's earlier detection that was available.
        source.stream().filter(row -> !row.getDetectedAt().isBefore(from) && !row.getDetectedAt().isAfter(asOf))
                .filter(row -> row.getReceivedAt() == null || !row.getReceivedAt().isAfter(availableBy))
                .sorted(Comparator.comparing(ScannerDetection::getDetectedAt).reversed())
                .forEach(row -> selected.computeIfAbsent(row.getStock().getId(), ignored ->
                        assess(row, from, asOf, minOpportunity, maxRisk, applyMovingAverage)));
        return List.copyOf(selected.values());
    }

    /**
     * QUALIFIED 대표 탐지만 점수 높은 순(동점이면 늦은 탐지 우선)으로 골라요.
     * 받는 것: representatives와 같은 입력과 요청 limit.
     * 돌려주는 것: 최대 min(limit, 5)개 평가 결과. 조건을 통과한 후보가 부족하면 채우지 않아요.
     */
    public List<Assessment> ranked(List<ScannerDetection> source, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk, int limit) {
        return representatives(source, from, asOf, minOpportunity, maxRisk).stream()
                .filter(row -> row.reason().equals("QUALIFIED"))
                .sorted(Comparator.comparing((Assessment row) -> row.score().score()).reversed()
                        .thenComparing(row -> row.detection().getDetectedAt(), Comparator.reverseOrder()))
                .limit(Math.min(limit, LIMITED_MODE_MAX_CANDIDATES)).toList();
    }

    /**
     * 탐지 1건을 이동평균 관문을 적용해 평가해요.
     * 받는 것: 탐지, 구간 시작, 기준 시각, 최소 기회·최대 위험 점수. 돌려주는 것: Assessment.
     */
    public Assessment assess(ScannerDetection detection, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk) {
        return assess(detection, from, asOf, minOpportunity, maxRisk, true);
    }

    /**
     * 탐지 1건의 결측·데이터 준비 상태·관문을 모두 확인하고 최종 판정 사유를 정해요.
     * 받는 것: 탐지, 구간 시작, 기준 시각, 최소 기회·최대 위험 점수, 이동평균 관문 적용 여부.
     * 돌려주는 것: 점수, 확정봉 수, 커버리지(분), 결측 목록, 판정 사유, 데이터 준비 상태 맵을 담은 Assessment.
     */
    private Assessment assess(ScannerDetection detection, Instant from, Instant asOf,
            BigDecimal minOpportunity, BigDecimal maxRisk, boolean applyMovingAverage) {
        // 준비: 결측 목록을 만들고, 탐지 당시 Feature 스냅샷에서 필수 값과 거래 가능 여부(거래정지)를 읽어요.
        List<String> missing = new ArrayList<>();
        Instant availableBy = asOf.plus(properties.candleFinalizationGrace());
        boolean haltedAtSignal = false;
        boolean tradabilityVerified = false;
        if (detection.getOpportunityScore() == null) missing.add("opportunityScore");
        if (detection.getRiskScore() == null) missing.add("riskScore");
        if (detection.getVolumeRatio() == null) missing.add("volumeRatio");
        try {
            JsonNode node = mapper.readTree(detection.getFeatureSnapshot());
            for (String field : List.of("vwapDistanceRate", "dayHighDistanceRate", "tradeStrength")) {
                if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) missing.add(field);
            }
            haltedAtSignal = node.path("tradingHalted").asBoolean(false);
            tradabilityVerified = node.hasNonNull("tradingHalted");
        } catch (Exception error) {
            missing.add("featureSnapshot");
        }
        // 거래정지 정보가 없거나, 신호가 30분 넘게 묵었거나, 수신 시각이 없거나 마감 뒤면 결측으로 남겨요.
        if (!tradabilityVerified) missing.add("TRADABILITY_UNVERIFIED");
        if (Duration.between(detection.getDetectedAt(), asOf).compareTo(MAX_SIGNAL_AGE) > 0)
            missing.add("STALE_FEATURE");
        if (detection.getReceivedAt() == null)
            missing.add("RECEIVED_AT_MISSING");
        else if (detection.getReceivedAt().isAfter(availableBy))
            missing.add("RECEIVED_AFTER_EVALUATION");

        // 처리 1: 구간 안의 5분봉을 보며 평가 시점에 실제로 저장돼 있던 확정봉만 남겨요.
        List<StockCandle> session = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        detection.getStock().getId(), "5M", from, asOf.plusSeconds(1));
        Set<Instant> starts = new HashSet<>();
        Set<Instant> unavailableAtEvaluation = new HashSet<>();
        boolean missingCandleTimestamp = false;
        StockCandle latest = null;
        for (StockCandle candle : session) {
            // 아직 확정되지 않은 봉과 기준 시각 이후에 끝나는 봉은 건너뛰어요.
            if (!candle.isFinalCandle() || candle.getStartTime().plus(Duration.ofMinutes(5)).isAfter(asOf))
                continue;
            // v9.1: a bar that only closes at the input deadline cannot be stored by then; it is not an input
            // at evaluation and not a late revision.
            if (!candle.getStartTime().plus(Duration.ofMinutes(5)).isBefore(availableBy))
                continue;
            // 생성·수정 시각이 없으면 수신 여부를 알 수 없고, 마감 뒤에 생성·수정됐으면 평가 때 쓸 수 없던 봉이에요.
            if (candle.getCreatedAt() == null || candle.getUpdatedAt() == null) {
                missingCandleTimestamp = true;
                continue;
            }
            if (candle.getCreatedAt().isAfter(availableBy) || candle.getUpdatedAt().isAfter(availableBy)) {
                unavailableAtEvaluation.add(candle.getStartTime());
                continue;
            }
            // 쓸 수 있는 봉 중 가장 최근 봉을 기억하고, 탐지 시각 이전에 끝난 봉 시작 시각을 모아요.
            if (latest == null || candle.getStartTime().isAfter(latest.getStartTime())) latest = candle;
            if (!candle.getStartTime().plus(Duration.ofMinutes(5)).isAfter(detection.getDetectedAt()))
                starts.add(candle.getStartTime());
        }
        // 탐지가 속한 5분 구간 바로 앞부터 거꾸로, 끊김 없이 이어진 확정봉 수를 세요.
        Instant bucket = detection.getDetectedAt().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        bucket = bucket.minusSeconds(bucket.atZone(java.time.ZoneId.of("Asia/Seoul")).getMinute() % 5 * 60L);
        int finalCandles = 0;
        Instant firstMissing = bucket.minus(Duration.ofMinutes(5));
        while (starts.contains(firstMissing)) {
            finalCandles++;
            firstMissing = firstMissing.minus(Duration.ofMinutes(5));
        }
        // 연속이 끊긴 자리나 최신 봉 이후에 늦게 저장된 봉이 있으면 늦은 수정으로 보고 결측 처리해요(덮어쓰지 않아요).
        // ⚠️ 확인 필요: 앞의 수신 시각 검사와 여기서 RECEIVED_AFTER_EVALUATION이 같은 목록에 두 번 들어갈 수 있어요. 판정에는 영향이 없어 보여요.
        Instant latestStart = latest == null ? null : latest.getStartTime();
        boolean lateRevision = unavailableAtEvaluation.contains(firstMissing)
                || (latestStart != null && unavailableAtEvaluation.stream().anyMatch(start -> start.isAfter(latestStart)));
        if (lateRevision) missing.add("RECEIVED_AFTER_EVALUATION");
        if (missingCandleTimestamp) missing.add("CANDLE_TIMESTAMP_MISSING");
        if (finalCandles < properties.minimumFinalCandles()) missing.add("CANDLE_GAP");

        // 처리 2: 장중·일봉 이동평균을 마감 시각 기준으로 계산하고, 일봉이 부족(21개 미만)하거나 전 거래일 기준이 아니면 결측이에요.
        IntradayMovingAverageFeature intraday = intradayMa.calculate(detection, availableBy);
        DailyMovingAverageFeature daily = dailyMa.calculate(detection, availableBy);
        if (applyMovingAverage && (daily == null || daily.candleCount() < 21 || !daily.ready()))
            missing.add("DAILY_DATA_MISSING");
        if (applyMovingAverage && daily != null && daily.ready() && daily.asOfDate() != null
                && !daily.asOfDate().equals(calendar.previousTradingDay(detection.getSessionDate())))
            missing.add("DAILY_DATA_STALE");
        // 관문 값: 일봉 추세(종가>MA20, MA20 상승), 최신 봉 신선도, 신호 유지, MA20 대비 과열, 거래대금 유동성.
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
                && detection.getDailyValue().compareTo(properties.minimumDailyTradingValue()) >= 0
                && latest.getTradingValue().compareTo(properties.minimumFiveMinuteTradingValue()) >= 0;
        // 점수: 운영 경로는 이동평균 Feature를 포함해 점수를 매기고, 실험 경로는 이동평균 없이 매겨요.
        ScoreResult score = applyMovingAverage
                ? scorer.score(detection, intraday == null ? IntradayMovingAverageFeature.empty(0) : intraday,
                        daily == null ? DailyMovingAverageFeature.empty(0) : daily)
                : scorer.score(detection);
        // 데이터 준비 상태를 근거로 남겨요. 시장 상황·섹터·계좌 노출은 아직 검증하지 않고, 주문 가능 여부는 항상 false예요(실주문 없음).
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("detectedAt", detection.getDetectedAt().toString());
        readiness.put("receivedAt", detection.getReceivedAt() == null ? null : detection.getReceivedAt().toString());
        readiness.put("evaluatedAsOf", asOf.toString());
        readiness.put("inputReceiptDeadline", availableBy.toString());
        readiness.put("decisionAvailableAfter", availableBy.toString());
        readiness.put("receiptVerified", detection.getReceivedAt() != null);
        readiness.put("tradabilityAtSignalVerified", tradabilityVerified);
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
        // 궤적 스냅샷은 기록만 하고 운영 점수에는 쓰지 않아요.
        if (trajectory != null) {
            var snapshot = trajectory.available(detection.getStock().getStockCode(), asOf, availableBy);
            readiness.put("trajectory", snapshot);
            readiness.put("trajectoryMode", "SHADOW_NOT_USED_IN_PRODUCTION_SCORE");
        }
        readiness.put("movingAverageExperiment", applyMovingAverage ? "PRODUCTION_BASELINE" : "REMOVED_THRESHOLD_55");
        // 결과: 위에서부터 처음 걸린 관문이 판정 사유가 돼요. 거래 불가 → 기회/위험 필터 → 결측 → 커버리지 → 일봉 추세 →
        // 최신 봉 → 신호 유지(INTRADAY_REVERSAL) → 과열 → 유동성 → 최소 점수 순이고, 모두 통과하면 QUALIFIED예요.
        String reason;
        Stock stock = detection.getStock();
        if (!stock.isActive() || stock.isManaged() || stock.isTradingHalted() || haltedAtSignal
                || stock.isEtf() || stock.isEtn())
            reason = "NOT_TRADABLE";
        else if (detection.getOpportunityScore() == null || detection.getRiskScore() == null
                || detection.getOpportunityScore().compareTo(minOpportunity) < 0
                || detection.getRiskScore().compareTo(maxRisk) > 0)
            reason = "OPPORTUNITY_OR_RISK_FILTERED";
        else if (!missing.isEmpty()) reason = missing.getFirst();
        else if (finalCandles * 5 < properties.minimumCoverageMinutes()) reason = "INSUFFICIENT_INTRADAY_COVERAGE";
        else if (applyMovingAverage && !dailyTrend) reason = "DAILY_TREND_WEAK";
        else if (!latestFresh) reason = "LATEST_CANDLE_STALE";
        else if (!retained) reason = "INTRADAY_REVERSAL";
        else if (applyMovingAverage && overextended) reason = "OVEREXTENDED";
        else if (!liquid) reason = "LOW_LIQUIDITY";
        else if (score.score().compareTo(properties.minimumFinalScore()) < 0) reason = "LOW_FINAL_SCORE";
        else reason = "QUALIFIED";
        return new Assessment(detection, score, finalCandles, finalCandles * 5, List.copyOf(missing), reason,
                java.util.Collections.unmodifiableMap(readiness));
    }

    /** 탐지 1건의 평가 결과예요. 탐지, 점수, 연속 확정봉 수, 커버리지(분), 결측 목록, 판정 사유, 데이터 준비 상태를 담아요. */
    public record Assessment(ScannerDetection detection, ScoreResult score, int finalCandles,
            int coverageMinutes, List<String> missingFeatures, String reason, Map<String, Object> dataReadiness) { }
}
