/*
 * 마감 추천(closing-recommend-v9_1-1515)의 핵심 서비스예요. 입력 마감(기본 15:15)까지 받은 정밀·BROAD 후보를 평가해
 * 최종 추천(SELECTED, 최대 5개), 관찰(WATCH), 제외(EXCLUDED)로 나누고, 평가 결과 전체를 run 스냅샷으로 남겨요.
 * ClosingRecommendationController(수동 실행·조회)와 ClosingRecommendationScheduler(공식 자동 실행)가 써요.
 */
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

/**
 * 마감 추천 평가를 실행하고 결과를 기록·조회하는 서비스예요.
 * 마감 시간에 문을 닫고, 그때까지 도착한 지원서만 심사해 합격(SELECTED)·대기(WATCH)·탈락(EXCLUDED)을 정한 뒤
 * 심사 기록 원본을 봉인해 보관하는 심사위원장 같아요. 같은 날 공식 심사는 한 번만 하고, 다시 물으면 봉인된 기록을 보여 줘요.
 * 실제 주문은 하지 않고, 점수는 상승 확률이 아니에요.
 */
@Service
public class ClosingRecommendationService {
    // 모든 날짜·시각 계산은 한국 시간 기준이에요.
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
    private final ClosingPaperEntryService paperEntries;

    /**
     * 탐지·추천·run 저장소, BROAD 스냅샷과 점수기, 정밀 평가기, 거래일 달력, 정책, 실시간 진단, 가상 진입 서비스 등을 받아요.
     */
    public ClosingRecommendationService(ScannerDetectionRepository detections,
            ClosingRecommendationRepository recommendations,
            MarketBroadSnapshotRepository broadSnapshots, BroadClosingRecommendationScorer broadScorer,
            ObjectMapper objectMapper, JdbcTemplate jdbc,
            ClosingTradingCalendar calendar,
            ClosingRecommendationProperties properties, ClosingRecommendationRunRepository runs,
            ClosingPrecisionEvaluator precisionEvaluator, RealtimeDiagnostics realtime,
            RealtimeMarketProperties realtimeProperties, ClosingPaperEntryService paperEntries) {
        this.paperEntries = paperEntries;
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

    /**
     * 요청 키 없이 평가를 실행해요. 아래 generate와 같아요.
     * 받는 것: 추천 날짜(없으면 오늘), 요청 개수, 최소 기회 점수, 최대 위험 점수. 돌려주는 것: 평가 응답.
     */
    @Transactional
    public GenerateResponse generate(LocalDate date, int limit, BigDecimal minOpportunity, BigDecimal maxRisk) {
        return generate(date, limit, minOpportunity, maxRisk, null);
    }

    /**
     * 지정한 날짜의 마감 평가를 실행하고 결과를 저장해요. 트랜잭션(여러 DB 작업을 한 묶음으로 처리하는 단위) 안에서 돌아요.
     * 받는 것: 추천 날짜(없으면 오늘), 요청 개수(1~30으로 맞춤), 최소 기회 점수(없으면 0), 최대 위험 점수(없으면 65), 요청 키(멱등 키).
     * 돌려주는 것: 후보별 평가, 최종 추천, 기준값, run 정보가 담긴 GenerateResponse. 같은 키로 이미 실행했으면 저장된 결과를 그대로 돌려줘요.
     */
    @Transactional
    public GenerateResponse generate(LocalDate date, int limit, BigDecimal minOpportunity, BigDecimal maxRisk,
            String requestKey) {
        // 준비 1: 미래 날짜는 막고, 오늘이면 입력 마감(+유예) 전에는 실행할 수 없어요(아직 받을 입력이 남았으니까요).
        LocalDate targetDate = date == null ? calendar.today() : date;
        Instant generatedAt = calendar.now();
        if (targetDate.isAfter(generatedAt.atZone(MARKET_ZONE).toLocalDate()))
            throw new com.sunmo.stockplatform.common.error.ApplicationException(
                    com.sunmo.stockplatform.common.error.ErrorCode.INVALID_REQUEST,
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Future recommendation date is not allowed");
        Instant decisionAt = targetDate.atTime(properties.featureFreezeAt()).atZone(MARKET_ZONE).toInstant();
        Instant inputReceiptDeadline = decisionAt.plus(properties.candleFinalizationGrace());
        Instant entryDeadline = targetDate.atTime(properties.entryDeadline()).atZone(MARKET_ZONE).toInstant();
        boolean today = targetDate.equals(calendar.today());
        if (today && generatedAt.isBefore(inputReceiptDeadline))
            throw invalid(properties.featureFreezeAt() + " 기준 입력 마감 전에는 후보 평가를 실행할 수 없습니다");
        // 실행 모드: 오늘·거래일·진입 마감 전이면 FORWARD(실제 장중 실시간 공식 평가), 아니면 REPLAY(지난 데이터를 다시 돌려 보는 평가)예요.
        // FORWARD는 실시간 시세가 끊겨 있으면 평가하지 않아요.
        String mode = today && calendar.isTradingDay(targetDate) && generatedAt.isBefore(entryDeadline)
                ? "FORWARD" : "REPLAY";
        if ("FORWARD".equals(mode)) {
            Instant lastTickAt = realtime.lastTickAt();
            if (lastTickAt == null || lastTickAt.isBefore(decisionAt.minus(realtimeProperties.staleTimeout())))
                throw new ApplicationException(ErrorCode.MARKET_DATA_STALE, HttpStatus.SERVICE_UNAVAILABLE,
                        properties.featureFreezeAt() + " 기준 실시간 시세가 최신 상태가 아닙니다. 소켓 수신 상태를 확인하세요");
        }
        // 준비 2: 요청 개수는 1~30으로 맞추지만, 최종 추천은 아래에서 LIMITED_MODE_MAX_CANDIDATES(5)로 다시 잘려요.
        // 평가 기준값 전체를 기록해 두고, 그 해시로 같은 키의 다른 설정 재사용을 막아요.
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        Map<String, Object> criteria = new TreeMap<>();
        criteria.put("minimumCoverageMinutes", properties.minimumCoverageMinutes());
        criteria.put("minimumFinalCandles", properties.minimumFinalCandles());
        criteria.put("minimumFinalScore", properties.minimumFinalScore());
        criteria.put("minimumDailyCandles", 21);
        criteria.put("maximumSignalAgeMinutes", 30);
        criteria.put("minimumDailyTradingValue", properties.minimumDailyTradingValue());
        criteria.put("minimumFiveMinuteTradingValue", properties.minimumFiveMinuteTradingValue());
        criteria.put("maximumMa20DistancePercent", ClosingPrecisionEvaluator.MAX_MA20_DISTANCE_PERCENT);
        criteria.put("limitedModeMaxCandidates", ClosingPrecisionEvaluator.LIMITED_MODE_MAX_CANDIDATES);
        criteria.put("marketSectorAccountChecks", "UNVERIFIED");
        criteria.put("minOpportunity", threshold(minOpportunity));
        criteria.put("maxRisk", riskLimit(maxRisk));
        criteria.put("limit", safeLimit);
        criteria.put("evaluationStart", properties.evaluationStart().toString());
        criteria.put("featureFreezeAt", properties.featureFreezeAt().toString());
        criteria.put("featureCutoffInstant", decisionAt.toString());
        criteria.put("inputReceiptDeadlineInstant", inputReceiptDeadline.toString());
        criteria.put("entryDeadline", properties.entryDeadline().toString());
        criteria.put("candleFinalizationGraceSeconds", properties.candleFinalizationGrace().toSeconds());
        criteria.put("entryModel", "FRESH_OBSERVED_TICK_AFTER_COMPLETION");
        criteria.put("inputAvailability", "EXCLUDE_UNAVAILABLE_AT_RECEIPT_DEADLINE");
        criteria.put("calendarSource", "WEEKEND_CONFIGURED_AND_VERIFIED_SPECIAL_CLOSURES");
        // FORWARD는 날짜별 공식 키(official-날짜-전략버전)를 쓰므로 하루에 공식 run은 하나예요. REPLAY는 요청 키나 임의 UUID를 써요.
        String settings = serialize(criteria);
        String hash = sha256(settings);
        String key = "FORWARD".equals(mode) ? "official-" + targetDate + "-" + ClosingRecommendation.STRATEGY_VERSION
                : requestKey == null ? UUID.randomUUID().toString() : requestKey;
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
        // 처리 1: run을 먼저 만들고, 평가 시작(기본 14:30)~입력 마감 사이 Scanner 탐지를 모아 종목별 대표 탐지로 평가해요.
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

        // 처리 2: 같은 구간의 BROAD 스냅샷을 가져오고, 정밀 후보 중 관문을 모두 통과한 것만 추천 가능 목록에 넣어요.
        List<MarketBroadSnapshot> broadSource = broadSnapshots.findClosingCandidates(targetDate, cutoff, decisionAt);
        List<ScoredCandidate> selectable = new ArrayList<>();
        for (ScoredCandidate candidate : precisionCandidates) {
            CandidateDecision decision = precisionDecision(candidate, minOpportunity, maxRisk);
            if (decision.disposition() == CandidateDisposition.SELECTED) selectable.add(candidate);
        }
        // 처리 3: 점수 높은 순(동점이면 늦은 탐지 우선)으로 줄 세워 최대 min(limit, 5)개만 최종 추천으로 골라요. 부족해도 채우지 않아요.
        List<ScoredCandidate> rankedCandidates = selectable.stream()
                .sorted(Comparator.comparing((ScoredCandidate item) -> item.score().score()).reversed()
                        .thenComparing(item -> item.candidate().observedAt(), Comparator.reverseOrder()))
                .toList();
        List<ScoredCandidate> candidates = rankedCandidates.stream()
                .limit(Math.min(safeLimit, ClosingPrecisionEvaluator.LIMITED_MODE_MAX_CANDIDATES)).toList();

        // 처리 4: 최종 추천에 순위를 매겨 저장하고, 정밀·BROAD 후보 전체의 평가 행을 만들어요.
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
        // 결과: 응답 스냅샷과 데이터 버전을 run에 봉인하고, FORWARD면 평가된 후보 전체의 가상 진입 대기를 준비해요(ADR-010).
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
        if ("FORWARD".equals(mode)) paperEntries.prepare(run, evaluations.stream().map(CandidateEvaluationResponse::stockCode).toList());
        return response;
    }

    /**
     * 값을 감사(audit) 기록용 JSON 문자열로 바꿔요.
     * 받는 것: 값. 돌려주는 것: JSON 문자열.
     */
    private String serialize(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Failed to preserve recommendation audit", error);
        }
    }

    /**
     * 문자열의 SHA-256 해시(16진수)를 계산해요. 설정 동일성 확인과 데이터 버전 이름에 써요.
     * 받는 것: 문자열. 돌려주는 것: 64자리 16진수 해시.
     */
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    /**
     * 잘못된 요청(400) 예외를 만들어요.
     * 받는 것: 메시지. 돌려주는 것: ApplicationException.
     */
    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    /**
     * run ID가 주어지면 그 run의 추천 날짜를 확인해 돌려줘요.
     * 받는 것: 날짜(선택), run ID(선택). 돌려주는 것: run의 날짜, run ID가 없으면 받은 날짜 그대로.
     * 날짜와 run이 서로 맞지 않으면 400 오류예요.
     */
    @Transactional(readOnly = true)
    public LocalDate runDate(LocalDate date, Long runId) {
        if (runId == null) return date;
        ClosingRecommendationRun run = runs.findById(runId).orElseThrow(() -> invalid("Unknown evaluation run"));
        if (date != null && !date.equals(run.getRecommendationDate())) throw invalid("Run does not belong to date");
        return run.getRecommendationDate();
    }

    /**
     * 그 날짜에 실행된 run 목록을 최신순으로 돌려줘요.
     * 받는 것: 날짜(없으면 오늘). 돌려주는 것: RunResponse 목록.
     */
    @Transactional(readOnly = true)
    public List<RunResponse> history(LocalDate date) {
        return runs.findByRecommendationDateOrderByIdDesc(date == null ? calendar.today() : date)
                .stream().map(RunResponse::from).toList();
    }

    /**
     * 정밀 후보와 BROAD 후보 전체의 평가 행(처분과 사유 포함)을 만들어요.
     * 받는 것: 탐지 목록, BROAD 스냅샷, 봉 커버리지, 점수 매긴 정밀 후보, 최종 선정 후보, 최소 기회·최대 위험 점수.
     * 돌려주는 것: 평가 행 목록(정밀 먼저, 그다음 BROAD).
     */
    private List<CandidateEvaluationResponse> evaluations(List<ScannerDetection> source,
            List<MarketBroadSnapshot> broadSource, Map<ScannerDetection, CandleCoverage> coverage,
            List<ScoredCandidate> scoredPrecision, List<ScoredCandidate> selected,
            BigDecimal minOpportunity, BigDecimal maxRisk) {
        List<CandidateEvaluationResponse> result = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        selected.forEach(row -> selectedIds.add(row.candidate().stock().getId()));
        // 정밀 후보: 선정됐으면 SELECTED, 관문은 통과했지만 5개 상한에 밀렸으면 WATCH(LIMITED_MODE_WATCH), 나머지는 사유별 처분이에요.
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
        // BROAD 후보: 종목별 최신 스냅샷 1건만 봐요. 정밀 후보와 같은 종목이면 PRECISION_DUPLICATE로 제외하고,
        // 조건을 통과해도 최종 추천이 아니라 관찰(BROAD_WATCH_ONLY)까지만 가요.
        // 참고: 그래서 한 종목이 정밀 행과 PRECISION_DUPLICATE 행으로 두 번 나올 수 있어요(의도된 기록으로 유지 중이에요).
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

    /**
     * 정밀 후보 중에 같은 종목이 있는지 확인해요.
     * 받는 것: 정밀 후보 목록, 종목 ID. 돌려주는 것: 있으면 true.
     */
    private boolean precisionStocksContain(List<ScoredCandidate> rows, Long id) {
        return rows.stream().anyMatch(row -> row.candidate().stock().getId().equals(id));
    }

    /**
     * 후보 1건과 처분 결정을 API 응답용 평가 행으로 옮겨요.
     * 받는 것: 점수 매긴 후보, 연속 확정봉 수, 처분 결정, Feature 스냅샷(BROAD는 null). 돌려주는 것: CandidateEvaluationResponse.
     */
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

    /**
     * 저장된 평가 결과를 조회해요. run ID가 없으면 그날의 최신 FORWARD run, 없으면 최신 run을 보여 줘요.
     * 받는 것: 날짜(없으면 오늘), run ID(선택). 돌려주는 것: 평가 응답, run이 없으면 null.
     */
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

    /**
     * run에 봉인된 응답 스냅샷을 읽어 응답 객체로 되살려요.
     * 받는 것: run. 돌려주는 것: GenerateResponse, 스냅샷이 비어 있으면(아직 미완료) null.
     */
    private GenerateResponse readEvaluation(ClosingRecommendationRun run) {
        if ("{}".equals(run.getResponseSnapshot())) return null;
        try { return objectMapper.readValue(run.getResponseSnapshot(), GenerateResponse.class).withRun(run); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Failed to read recommendation audit", error);
        }
    }

    /**
     * BROAD 스냅샷 1건으로 BROAD 후보를 만들어요.
     * 받는 것: 스냅샷. 돌려주는 것: 후보와 BROAD 점수. 위험 점수는 45점 + 빠진 항목당 5점(최대 100)이에요.
     */
    private ScoredCandidate broadCandidate(MarketBroadSnapshot snapshot) {
        List<String> missing = broadMissing(snapshot);
        BigDecimal risk = bd("45").add(BigDecimal.valueOf(missing.size() * 5L)).min(bd("100"));
        ClosingCandidate.Broad candidate = new ClosingCandidate.Broad(snapshot, risk, missing);
        return new ScoredCandidate(candidate, broadScorer.score(snapshot, missing), null, Map.of());
    }

    /**
     * 최종 추천 후보를 저장용 추천 엔티티로 만들어요.
     * 받는 것: 날짜, 생성 시각, 후보, 순위. 돌려주는 것: ClosingRecommendation(정밀이면 탐지 기반, BROAD면 스냅샷 기반).
     */
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

    /**
     * 스냅샷 목록에서 종목별 첫 번째 스냅샷만 남겨요. 조회가 수집 시각 최신순이라 종목별 최신 1건이 돼요.
     * 받는 것: 스냅샷 목록. 돌려주는 것: 종목 ID별 스냅샷 Map.
     */
    private Map<Long, MarketBroadSnapshot> latestBroadByStock(List<MarketBroadSnapshot> source) {
        Map<Long, MarketBroadSnapshot> latest = new LinkedHashMap<>();
        source.forEach(snapshot -> latest.putIfAbsent(snapshot.getStock().getId(), snapshot));
        return latest;
    }

    /**
     * BROAD 후보가 관찰 대상이 될 만큼 데이터가 갖춰졌는지 확인해요.
     * 받는 것: 스냅샷. 돌려주는 것: 품질 BROAD_C, 수집 완료, 가격·거래대금·등락률 있음, 거래 가능 종목이면 true.
     */
    private boolean eligibleBroad(MarketBroadSnapshot snapshot) {
        return snapshot.getDataQuality() == BroadSnapshotQuality.BROAD_C
                && snapshot.getCollectionStatus() == BroadSnapshotStatus.COLLECTED
                && snapshot.getCurrentPrice() != null && snapshot.getAccumulatedTradingValue() != null
                && snapshot.getChangeRate() != null && tradable(snapshot.getStock());
    }

    /**
     * BROAD 스냅샷에서 빠진 Feature 목록을 만들어요. VWAP와 거래량 비율은 BROAD에서 구할 수 없어 항상 빠진 것으로 봐요.
     * 받는 것: 스냅샷. 돌려주는 것: 빠진 Feature 이름 목록.
     */
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

    /**
     * 빠진 Feature 목록을 JSON 문자열로 바꿔요.
     * 받는 것: 문자열 목록. 돌려주는 것: JSON 문자열.
     */
    private String json(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception error) { throw new IllegalStateException("Failed to serialize missing features", error); }
    }

    /**
     * 그 날짜의 저장된 최종 추천 목록을 순위순으로 돌려줘요.
     * 받는 것: 날짜(없으면 오늘). 돌려주는 것: RecommendationResponse 목록.
     */
    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(LocalDate date) {
        return list(date, null);
    }

    /**
     * 날짜 또는 run ID 기준으로 저장된 최종 추천 목록을 순위순으로 돌려줘요.
     * 받는 것: 날짜(선택), run ID(선택). 돌려주는 것: RecommendationResponse 목록.
     */
    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(LocalDate date, Long runId) {
        LocalDate checked = runDate(date, runId);
        LocalDate targetDate = checked == null ? calendar.today() : checked;
        return (runId == null ? recommendations.findByRecommendationDateOrderByRankAsc(targetDate)
                : recommendations.findByRunIdOrderByRankAsc(runId)).stream()
                .map(RecommendationResponse::from)
                .toList();
    }

    /**
     * 정밀 평가 사유를 처분으로 바꿔요.
     * 받는 것: 점수 매긴 후보, 최소·최대 점수(현재 쓰지 않아요). 돌려주는 것: QUALIFIED면 SELECTED,
     * 거래 불가·필터·약한 추세·반전·과열·유동성·낮은 점수면 EXCLUDED, 결측·커버리지 부족 등 나머지는 WATCH.
     */
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

    /**
     * 거래 가능한 종목인지 확인해요(상장 활성, 관리종목 아님, 거래정지 아님, ETF·ETN 아님).
     * 받는 것: 종목. 돌려주는 것: 거래 가능하면 true.
     */
    private boolean tradable(com.sunmo.stockplatform.stock.domain.Stock stock) {
        return stock.isActive() && !stock.isManaged() && !stock.isTradingHalted() && !stock.isEtf() && !stock.isEtn();
    }

    // 기준값 기본: 최소 기회 점수가 없으면 0, 최대 위험 점수가 없으면 65예요.
    private BigDecimal threshold(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal riskLimit(BigDecimal value) { return value == null ? bd("65") : value; }

    /**
     * 문자열을 BigDecimal로 바꿔요.
     */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 점수를 매긴 후보예요. 후보, 점수 결과, 정밀 판정 사유(BROAD는 null), 데이터 준비 상태를 담아요. */
    private record ScoredCandidate(ClosingCandidate candidate, ScoreResult score, String reason,
            Map<String, Object> dataReadiness) {
    }

    /** 정밀 후보의 연속 확정봉 수와 커버리지(분)예요. empty()는 0, 0이에요. */
    private record CandleCoverage(int finalCandles, int minutes) {
        private static CandleCoverage empty() { return new CandleCoverage(0, 0); }
    }

    // 처분 종류(SELECTED 최종 추천, WATCH 관찰, EXCLUDED 제외)와, 처분과 사유를 묶은 결정이에요.
    private enum CandidateDisposition { SELECTED, WATCH, EXCLUDED }
    private record CandidateDecision(CandidateDisposition disposition, String reason) { }
}
