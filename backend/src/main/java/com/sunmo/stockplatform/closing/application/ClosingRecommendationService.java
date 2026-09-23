package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.GenerateResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.RecommendationResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.CandidateEvaluationResponse;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.closing.application.ClosingRecommendationScorer.ScoreResult;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRepository;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.RunResponse;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.marketwide.domain.BroadSnapshotQuality;
import com.sunmo.stockplatform.marketwide.domain.BroadSnapshotStatus;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class ClosingRecommendationService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final ScannerDetectionRepository detections;
    private final ClosingRecommendationRepository recommendations;
    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final MarketBroadSnapshotRepository broadSnapshots;
    private final BroadClosingRecommendationScorer broadScorer;
    private final ObjectMapper objectMapper;
    private final ClosingRecommendationProperties properties;
    private final ClosingRecommendationRunRepository runs;
    private final ClosingPrecisionEvaluator precisionEvaluator;
    private final RealtimeDiagnostics realtime;
    private final RealtimeMarketProperties realtimeProperties;

    public ClosingRecommendationService(ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations,
            MarketBroadSnapshotRepository broadSnapshots, BroadClosingRecommendationScorer broadScorer,
            ObjectMapper objectMapper, JdbcTemplate jdbc,
            ClosingTradingCalendar calendar,
            ClosingRecommendationProperties properties, ClosingRecommendationRunRepository runs,
            ClosingPrecisionEvaluator precisionEvaluator, RealtimeDiagnostics realtime,
            RealtimeMarketProperties realtimeProperties) {
        this.detections = detections;
        this.recommendations = recommendations;
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.broadSnapshots = broadSnapshots;
        this.broadScorer = broadScorer;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.runs = runs;
        this.precisionEvaluator = precisionEvaluator;
        this.realtime = realtime;
        this.realtimeProperties = realtimeProperties;
    }

    @Transactional
    public GenerateResponse generate(LocalDate date, int limit, BigDecimal minOpportunity, BigDecimal maxRisk) {
        return generate(date, limit, minOpportunity, maxRisk, null);
    }

    @Transactional
    public GenerateResponse generate(LocalDate date, int limit, BigDecimal minOpportunity, BigDecimal maxRisk,
            String requestKey) {
        LocalDate targetDate = date == null ? calendar.today() : date;
        Instant generatedAt = calendar.now();
        if (targetDate.isAfter(generatedAt.atZone(MARKET_ZONE).toLocalDate()))
            throw new com.sunmo.stockplatform.common.error.ApplicationException(
                    com.sunmo.stockplatform.common.error.ErrorCode.INVALID_REQUEST,
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Future recommendation date is not allowed");
        Instant decisionAt = targetDate.atTime(properties.featureFreezeAt()).atZone(MARKET_ZONE).toInstant();
        Instant entryDeadline = targetDate.atTime(properties.entryDeadline()).atZone(MARKET_ZONE).toInstant();
        boolean today = targetDate.equals(calendar.today());
        if (today && generatedAt.isBefore(decisionAt))
            throw invalid("15:00 판단 시각 이전에는 후보 평가를 실행할 수 없습니다");
        String mode = today && calendar.isTradingDay(targetDate) && !generatedAt.isAfter(entryDeadline)
                ? "FORWARD" : "REPLAY";
        if ("FORWARD".equals(mode)) {
            Instant lastTickAt = realtime.lastTickAt();
            if (lastTickAt == null || lastTickAt.isBefore(decisionAt.minus(realtimeProperties.staleTimeout())))
                throw new ApplicationException(ErrorCode.MARKET_DATA_STALE, HttpStatus.SERVICE_UNAVAILABLE,
                        "15:00 기준 실시간 시세가 최신 상태가 아닙니다. 소켓 수신 상태를 확인하세요");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        Map<String, Object> criteria = new TreeMap<>();
        criteria.put("minimumCoverageMinutes", properties.minimumCoverageMinutes());
        criteria.put("minimumFinalCandles", properties.minimumFinalCandles());
        criteria.put("minimumFinalScore", properties.minimumFinalScore());
        criteria.put("minimumDailyCandles", 21);
        criteria.put("maximumSignalAgeMinutes", 30);
        criteria.put("minimumDailyTradingValue", ClosingPrecisionEvaluator.MIN_DAILY_VALUE);
        criteria.put("minimumFiveMinuteTradingValue", ClosingPrecisionEvaluator.MIN_FIVE_MINUTE_VALUE);
        criteria.put("maximumMa20DistancePercent", ClosingPrecisionEvaluator.MAX_MA20_DISTANCE_PERCENT);
        criteria.put("limitedModeMaxCandidates", ClosingPrecisionEvaluator.LIMITED_MODE_MAX_CANDIDATES);
        criteria.put("marketSectorAccountChecks", "UNVERIFIED");
        criteria.put("minOpportunity", threshold(minOpportunity));
        criteria.put("maxRisk", riskLimit(maxRisk));
        criteria.put("limit", safeLimit);
        criteria.put("evaluationStart", properties.evaluationStart().toString());
        criteria.put("featureFreezeAt", properties.featureFreezeAt().toString());
        criteria.put("entryDeadline", properties.entryDeadline().toString());
        criteria.put("candleFinalizationGraceSeconds", properties.candleFinalizationGrace().toSeconds());
        criteria.put("entryModel", "NEXT_FINAL_5M_OPEN");
        String settings = serialize(criteria);
        String hash = sha256(settings);
        String key = requestKey == null ? UUID.randomUUID().toString() : requestKey;
        if (!key.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("Invalid Idempotency-Key");
        // A transaction-scoped database lock also serializes retries across backend instances.
        jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtextextended(?, 0))", Integer.class, key);
        Optional<ClosingRecommendationRun> previous = runs.findByRequestKey(key);
        if (previous.isPresent()) {
            ClosingRecommendationRun run = previous.get();
            if (!targetDate.equals(run.getRecommendationDate()) || !hash.equals(run.getSettingsHash()))
                throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                        "Idempotency-Key already used with different settings");
            return readEvaluation(run);
        }
        Instant cutoff = targetDate.atTime(properties.evaluationStart()).atZone(MARKET_ZONE).toInstant();
        ClosingRecommendationRun run = runs.save(new ClosingRecommendationRun(targetDate, generatedAt,
                ClosingRecommendation.STRATEGY_VERSION, mode, decisionAt, settings, hash, key));
        List<ScannerDetection> source = detections
                .findBySessionDateAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(targetDate, cutoff);

        List<ScannerDetection> boundedDetections = source.stream()
                .filter(detection -> !detection.getDetectedAt().isAfter(decisionAt)).toList();
        List<ClosingPrecisionEvaluator.Assessment> assessed = precisionEvaluator.representatives(boundedDetections,
                cutoff, decisionAt, threshold(minOpportunity), riskLimit(maxRisk));
        Map<ScannerDetection, CandleCoverage> coverage = new HashMap<>();
        List<ScoredCandidate> precisionCandidates = new ArrayList<>();
        for (ClosingPrecisionEvaluator.Assessment row : assessed) {
            coverage.put(row.detection(), new CandleCoverage(row.finalCandles(), row.coverageMinutes()));
            precisionCandidates.add(new ScoredCandidate(new ClosingCandidate.Precision(row.detection(),
                    row.coverageMinutes(), row.missingFeatures()), row.score(), row.reason(), row.dataReadiness()));
        }

        List<MarketBroadSnapshot> broadSource = broadSnapshots.findClosingCandidates(targetDate, cutoff, decisionAt);
        List<ScoredCandidate> selectable = new ArrayList<>();
        for (ScoredCandidate candidate : precisionCandidates) {
            CandidateDecision decision = precisionDecision(candidate, minOpportunity, maxRisk);
            if (decision.disposition() == CandidateDisposition.SELECTED) selectable.add(candidate);
        }
        List<ScoredCandidate> rankedCandidates = selectable.stream()
                .sorted(Comparator.comparing((ScoredCandidate item) -> item.score().score()).reversed()
                        .thenComparing(item -> item.candidate().observedAt(), Comparator.reverseOrder()))
                .toList();
        List<ScoredCandidate> candidates = rankedCandidates.stream()
                .limit(Math.min(safeLimit, ClosingPrecisionEvaluator.LIMITED_MODE_MAX_CANDIDATES)).toList();

        List<ClosingRecommendation> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ScoredCandidate candidate = candidates.get(index);
            ClosingRecommendation row = entity(targetDate, generatedAt, candidate, index + 1);
            row.assignRun(run);
            ranked.add(row);
        }
        List<ClosingRecommendation> saved = recommendations.saveAll(ranked);
        List<CandidateEvaluationResponse> evaluations = evaluations(boundedDetections, broadSource, coverage,
                precisionCandidates, candidates, minOpportunity, maxRisk);
        Map<String, Integer> decisionReasons = new LinkedHashMap<>();
        evaluations.stream().filter(row -> !row.disposition().equals("SELECTED"))
                .forEach(row -> decisionReasons.merge(row.decisionReason(), 1, Integer::sum));
        GenerateResponse response = new GenerateResponse(
                targetDate,
                generatedAt,
                boundedDetections.size(),
                broadSource.size(),
                saved.size(),
                (int) evaluations.stream().filter(row -> row.disposition().equals("WATCH")).count(),
                (int) evaluations.stream().filter(row -> row.disposition().equals("EXCLUDED")).count(),
                Map.copyOf(decisionReasons),
                ClosingRecommendation.STRATEGY_VERSION,
                saved.stream().map(RecommendationResponse::from).toList(), decisionAt,
                criteria, Map.copyOf(decisionReasons), evaluations, run.getId(), mode, calendar.now());
        run.recordDataVersion("snapshot-" + sha256(serialize(evaluations)).substring(0, 31));
        run.complete(serialize(response), response.completedAt());
        return response;
    }

    private String serialize(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Failed to preserve recommendation audit", error);
        }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    @Transactional(readOnly = true)
    public LocalDate runDate(LocalDate date, Long runId) {
        if (runId == null) return date;
        ClosingRecommendationRun run = runs.findById(runId).orElseThrow(() -> invalid("Unknown evaluation run"));
        if (date != null && !date.equals(run.getRecommendationDate())) throw invalid("Run does not belong to date");
        return run.getRecommendationDate();
    }

    @Transactional(readOnly = true)
    public List<RunResponse> history(LocalDate date) {
        return runs.findByRecommendationDateOrderByIdDesc(date == null ? calendar.today() : date)
                .stream().map(RunResponse::from).toList();
    }

    private List<CandidateEvaluationResponse> evaluations(List<ScannerDetection> source,
            List<MarketBroadSnapshot> broadSource, Map<ScannerDetection, CandleCoverage> coverage,
            List<ScoredCandidate> scoredPrecision, List<ScoredCandidate> selected,
            BigDecimal minOpportunity, BigDecimal maxRisk) {
        List<CandidateEvaluationResponse> result = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        selected.forEach(row -> selectedIds.add(row.candidate().stock().getId()));
        for (ScoredCandidate candidate : scoredPrecision) {
            ScannerDetection detection = ((ClosingCandidate.Precision) candidate.candidate()).detection();
            Long id = detection.getStock().getId();
            CandleCoverage observed = coverage.getOrDefault(detection, CandleCoverage.empty());
            CandidateDecision decision;
            if (selectedIds.contains(id)) decision = new CandidateDecision(CandidateDisposition.SELECTED, "QUALIFIED");
            else {
                decision = precisionDecision(candidate, minOpportunity, maxRisk);
                if (decision.disposition() == CandidateDisposition.SELECTED)
                    decision = new CandidateDecision(CandidateDisposition.WATCH, "LIMITED_MODE_WATCH");
            }
            result.add(evaluation(candidate, observed.finalCandles(), decision, detection.getFeatureSnapshot()));
        }
        for (MarketBroadSnapshot snapshot : latestBroadByStock(broadSource).values()) {
            ScoredCandidate candidate = broadCandidate(snapshot);
            String reason = precisionStocksContain(scoredPrecision, snapshot.getStock().getId()) ? "PRECISION_DUPLICATE"
                    : !eligibleBroad(snapshot) ? "INSUFFICIENT_BROAD_DATA"
                    : candidate.candidate().opportunityScore().compareTo(threshold(minOpportunity)) < 0
                        || candidate.candidate().riskScore().compareTo(riskLimit(maxRisk)) > 0
                            ? "OPPORTUNITY_OR_RISK_FILTERED" : "BROAD_WATCH_ONLY";
            result.add(evaluation(candidate, 0, new CandidateDecision(reason.equals("BROAD_WATCH_ONLY")
                    ? CandidateDisposition.WATCH : CandidateDisposition.EXCLUDED, reason), null));
        }
        return List.copyOf(result);
    }

    private boolean precisionStocksContain(List<ScoredCandidate> rows, Long id) {
        return rows.stream().anyMatch(row -> row.candidate().stock().getId().equals(id));
    }

    private CandidateEvaluationResponse evaluation(ScoredCandidate scored, int finalCandles,
            CandidateDecision decision, String featureSnapshot) {
        ClosingCandidate candidate = scored.candidate();
        return new CandidateEvaluationResponse(candidate.stock().getStockCode(), candidate.stock().getStockName(),
                candidate.source().name(), candidate instanceof ClosingCandidate.Precision precision
                        ? precision.detection().getType().name() : null,
                candidate.observedAt(), candidate.referencePrice(), scored.score().score(), candidate.opportunityScore(),
                candidate.riskScore(), candidate.dataQuality(), finalCandles, candidate.coverageMinutes(),
                candidate.missingFeatures(), decision.disposition().name(), decision.reason(),
                scored.score().recommendationReason(), scored.score().riskReason(), featureSnapshot,
                scored.dataReadiness());
    }

    @Transactional(readOnly = true)
    public GenerateResponse latestEvaluation(LocalDate date) {
        return evaluation(date, null);
    }

    @Transactional(readOnly = true)
    public GenerateResponse evaluation(LocalDate date, Long runId) {
        runDate(date, runId);
        LocalDate targetDate = date == null ? calendar.today() : date;
        Optional<ClosingRecommendationRun> selected = runId == null
                ? runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(targetDate, "FORWARD")
                        .or(() -> runs.findFirstByRecommendationDateOrderByIdDesc(targetDate))
                : runs.findById(runId);
        return selected.map(this::readEvaluation).orElse(null);
    }

    private GenerateResponse readEvaluation(ClosingRecommendationRun run) {
        if ("{}".equals(run.getResponseSnapshot())) return null;
        try { return objectMapper.readValue(run.getResponseSnapshot(), GenerateResponse.class).withRun(run); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Failed to read recommendation audit", error);
        }
    }

    private ScoredCandidate broadCandidate(MarketBroadSnapshot snapshot) {
        List<String> missing = broadMissing(snapshot);
        BigDecimal risk = bd("45").add(BigDecimal.valueOf(missing.size() * 5L)).min(bd("100"));
        ClosingCandidate.Broad candidate = new ClosingCandidate.Broad(snapshot, risk, missing);
        return new ScoredCandidate(candidate, broadScorer.score(snapshot, missing), null, Map.of());
    }

    private ClosingRecommendation entity(LocalDate date, Instant generatedAt, ScoredCandidate scored, int rank) {
        String missing = json(scored.candidate().missingFeatures());
        if (scored.candidate() instanceof ClosingCandidate.Precision precision) {
            return new ClosingRecommendation(date, generatedAt, precision.detection(), rank, scored.score().score(),
                    scored.score().recommendationReason(), scored.score().riskReason(), precision.dataQuality(),
                    precision.coverageMinutes(), missing);
        }
        ClosingCandidate.Broad broad = (ClosingCandidate.Broad) scored.candidate();
        return new ClosingRecommendation(date, generatedAt, broad.snapshot(), rank, scored.score().score(),
                broad.opportunityScore(), broad.riskScore(), scored.score().recommendationReason(),
                scored.score().riskReason(), missing);
    }

    private Map<Long, MarketBroadSnapshot> latestBroadByStock(List<MarketBroadSnapshot> source) {
        Map<Long, MarketBroadSnapshot> latest = new LinkedHashMap<>();
        source.forEach(snapshot -> latest.putIfAbsent(snapshot.getStock().getId(), snapshot));
        return latest;
    }

    private boolean eligibleBroad(MarketBroadSnapshot snapshot) {
        return snapshot.getDataQuality() == BroadSnapshotQuality.BROAD_C
                && snapshot.getCollectionStatus() == BroadSnapshotStatus.COLLECTED
                && snapshot.getCurrentPrice() != null && snapshot.getAccumulatedTradingValue() != null
                && snapshot.getChangeRate() != null && tradable(snapshot.getStock());
    }

    private List<String> broadMissing(MarketBroadSnapshot snapshot) {
        List<String> missing = new ArrayList<>();
        if (snapshot.getCurrentPrice() == null) missing.add("currentPrice");
        if (snapshot.getChangeRate() == null) missing.add("changeRate");
        if (snapshot.getAccumulatedVolume() == null) missing.add("accumulatedVolume");
        if (snapshot.getAccumulatedTradingValue() == null) missing.add("accumulatedTradingValue");
        if (snapshot.getTradeStrength() == null) missing.add("tradeStrength");
        missing.add("vwap");
        missing.add("volumeRatio");
        return List.copyOf(missing);
    }

    private String json(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception error) { throw new IllegalStateException("Failed to serialize missing features", error); }
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(LocalDate date) {
        return list(date, null);
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(LocalDate date, Long runId) {
        LocalDate checked = runDate(date, runId);
        LocalDate targetDate = checked == null ? calendar.today() : checked;
        return (runId == null ? recommendations.findByRecommendationDateOrderByRankAsc(targetDate)
                : recommendations.findByRunIdOrderByRankAsc(runId)).stream()
                .map(RecommendationResponse::from)
                .toList();
    }

    private CandidateDecision precisionDecision(ScoredCandidate row, BigDecimal minimum, BigDecimal maximum) {
        if (row.reason() == null || row.reason().equals("QUALIFIED"))
            return new CandidateDecision(CandidateDisposition.SELECTED, "QUALIFIED");
        CandidateDisposition disposition = switch (row.reason()) {
            case "NOT_TRADABLE", "OPPORTUNITY_OR_RISK_FILTERED", "LOW_FINAL_SCORE",
                    "DAILY_TREND_WEAK", "INTRADAY_REVERSAL", "OVEREXTENDED", "LOW_LIQUIDITY" -> CandidateDisposition.EXCLUDED;
            default -> CandidateDisposition.WATCH;
        };
        return new CandidateDecision(disposition, row.reason());
    }

    private boolean tradable(com.sunmo.stockplatform.stock.domain.Stock stock) {
        return stock.isActive() && !stock.isManaged() && !stock.isTradingHalted() && !stock.isEtf() && !stock.isEtn();
    }

    private BigDecimal threshold(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal riskLimit(BigDecimal value) { return value == null ? bd("65") : value; }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record ScoredCandidate(ClosingCandidate candidate, ScoreResult score, String reason,
            Map<String, Object> dataReadiness) {
    }

    private record CandleCoverage(int finalCandles, int minutes) {
        private static CandleCoverage empty() { return new CandleCoverage(0, 0); }
    }

    private enum CandidateDisposition { SELECTED, WATCH, EXCLUDED }
    private record CandidateDecision(CandidateDisposition disposition, String reason) { }
}
