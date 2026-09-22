package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendation;
import com.sunmo.stockplatform.closing.domain.OvernightPerformance;
import com.sunmo.stockplatform.closing.infrastructure.OvernightPerformanceRepository;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ClosingStrategyAnalyticsService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int RECENT_RECOMMENDATION_DAYS = 7;
    private static final int BASELINE_RECOMMENDATION_DAYS = 20;
    private static final List<String> VALIDATED_FEATURES = List.of(
            "vwapDistanceRate", "dayHighDistanceRate", "tradeStrength", "turnoverRatio");
    private final OvernightPerformanceRepository performances;
    private final ClosingTradingCalendar calendar;
    private final ObjectMapper mapper;

    public ClosingStrategyAnalyticsService(OvernightPerformanceRepository performances,
            ClosingTradingCalendar calendar, ObjectMapper mapper) {
        this.performances = performances;
        this.calendar = calendar;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public StrategyAnalyticsReport analyze(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? calendar.today() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end) || start.isBefore(end.minusDays(730)))
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "분석 기간은 종료일 이전이며 최대 730일이어야 합니다");
        List<OvernightPerformance> rows = performances.findOfficialCompletedBetween(start, end,
                OvernightPerformance.OBSERVATION_VERSION);
        Set<BigDecimal> targetRates = rows.stream().map(OvernightPerformance::getTargetRate)
                .filter(Objects::nonNull).map(BigDecimal::stripTrailingZeros)
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> warnings = new ArrayList<>();
        warnings.add("신호가격 기준 시장 관측이며 실제 또는 모의 체결 순수익이 아닙니다.");
        if (rows.size() < 30)
            warnings.add("공식 전진 표본이 30건 미만이므로 결과는 탐색적으로만 해석하세요.");
        if (targetRates.size() > 1)
            warnings.add("서로 다른 목표수익률이 포함되어 점수 구간을 직접 비교하기 어렵습니다.");
        OosValidation oos = oosValidation(rows, warnings);
        PerformanceMonitoring monitoring = monitoring(rows, warnings);
        ContextCoverage context = new ContextCoverage(true, false, false,
                "시장 구분 성과는 제공하지만 시점별 지수 Regime과 업종 이력 데이터는 아직 없습니다.");
        return new StrategyAnalyticsReport(start, end, calendar.now(), rows.size(),
                "OFFICIAL_FORWARD_SIGNAL_PRICE_OBSERVATION_ONLY", List.copyOf(targetRates), List.copyOf(warnings),
                scoreBands(rows), segments(rows), lossPatterns(rows), oos, monitoring, context,
                promotionGate(oos, monitoring, context));
    }

    private StrategyPromotionGate promotionGate(OosValidation oos, PerformanceMonitoring monitoring,
            ContextCoverage context) {
        List<ReadinessCheck> checks = List.of(
                new ReadinessCheck("OFFICIAL_OOS", "공식 시간순 표본 외 검증", "READY".equals(oos.status()),
                        "개발·검증 구간의 최소 표본과 시점 분리가 필요합니다."),
                new ReadinessCheck("RECENT_STABILITY", "최근 전략 안정성", "OBSERVE".equals(monitoring.status()),
                        "최근 성과 감시가 준비되고 악화 상태가 아니어야 합니다."),
                new ReadinessCheck("NET_EXECUTION_OUTCOMES", "비용 반영 공식 모의체결 성과", false,
                        "현재 공식 성과는 신호가격 관측이며 모의 주문·체결 순수익 원장이 없습니다."),
                new ReadinessCheck("MARKET_CONTEXT", "시점별 시장·업종 맥락", context.marketRegimeAvailable()
                        && context.sectorHistoryAvailable(), "지수 원시값과 유효기간이 있는 업종 이력이 필요합니다."),
                new ReadinessCheck("TIMESTAMPED_EVENTS", "공개·수신 시각이 보존된 뉴스·공시", false,
                        "검증 가능한 이벤트 데이터 원천과 정정 이력이 없습니다."));
        boolean statisticalReady = checks.stream().filter(check -> !check.code().equals("TIMESTAMPED_EVENTS"))
                .allMatch(ReadinessCheck::passed);
        boolean eventReady = checks.stream().allMatch(ReadinessCheck::passed);
        return new StrategyPromotionGate("RULE_BASED_V8_BASELINE", "KEEP_BASELINE",
                statisticalReady ? "READY_FOR_EXPERIMENT" : "BLOCKED",
                eventReady ? "READY_FOR_EXPERIMENT" : "BLOCKED", false, List.copyOf(checks));
    }

    private PerformanceMonitoring monitoring(List<OvernightPerformance> rows, List<String> warnings) {
        List<LocalDate> dates = rows.stream().map(row -> row.getRecommendation().getRecommendationDate())
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (dates.size() <= RECENT_RECOMMENDATION_DAYS) {
            warnings.add("최근 성과 악화 비교에 필요한 추천일이 부족합니다.");
            return PerformanceMonitoring.empty();
        }
        int recentStart = dates.size() - RECENT_RECOMMENDATION_DAYS;
        int baselineStart = Math.max(0, recentStart - BASELINE_RECOMMENDATION_DAYS);
        Set<LocalDate> recentDates = new HashSet<>(dates.subList(recentStart, dates.size()));
        Set<LocalDate> baselineDates = new HashSet<>(dates.subList(baselineStart, recentStart));
        List<OvernightPerformance> recent = rows.stream()
                .filter(row -> recentDates.contains(row.getRecommendation().getRecommendationDate())).toList();
        List<OvernightPerformance> baseline = rows.stream()
                .filter(row -> baselineDates.contains(row.getRecommendation().getRecommendationDate())).toList();
        MonitoringMetrics baselineMetrics = monitoringMetrics(baseline);
        MonitoringMetrics recentMetrics = monitoringMetrics(recent);
        boolean enough = baselineDates.size() >= 10 && recentDates.size() == RECENT_RECOMMENDATION_DAYS;
        boolean degraded = enough && negative(recentMetrics.averageCloseReturn())
                && lower(recentMetrics.averageCloseReturn(), baselineMetrics.averageCloseReturn())
                && lower(recentMetrics.targetHitRate(), baselineMetrics.targetHitRate());
        String status = !enough ? "INSUFFICIENT_SAMPLE" : degraded ? "DEGRADED" : "OBSERVE";
        if (!enough) warnings.add("최근 7개 추천일과 이전 10개 이상 추천일이 쌓이기 전에는 악화 상태를 판정하지 않습니다.");
        if (degraded) warnings.add("최근 공식 관측의 종가수익과 목표 도달률이 이전 구간보다 함께 낮아 운영 검토가 필요합니다.");
        return new PerformanceMonitoring(status, BASELINE_RECOMMENDATION_DAYS, RECENT_RECOMMENDATION_DAYS,
                period(baseline), period(recent), baselineMetrics, recentMetrics,
                subtract(recentMetrics.averageCloseReturn(), baselineMetrics.averageCloseReturn()),
                subtract(recentMetrics.targetHitRate(), baselineMetrics.targetHitRate()),
                failureTrends(baseline, recent));
    }

    private MonitoringMetrics monitoringMetrics(List<OvernightPerformance> rows) {
        int targets = (int) rows.stream().filter(OvernightPerformance::isTargetHit).count();
        int stops = (int) rows.stream().filter(OvernightPerformance::isStopHit).count();
        return new MonitoringMetrics(rows.size(), rate(targets, rows.size()), rate(stops, rows.size()),
                average(rows, OvernightPerformance::getCloseReturnRate),
                average(rows, OvernightPerformance::getMaxDrawdownRate));
    }

    private List<FailureTrend> failureTrends(List<OvernightPerformance> baseline, List<OvernightPerformance> recent) {
        return List.of(
                failureTrend("GAP_DOWN", baseline, recent, row -> negative(row.getOpenReturnRate())),
                failureTrend("CLOSE_LOSS", baseline, recent, row -> negative(row.getCloseReturnRate())),
                failureTrend("STOP_HIT", baseline, recent, OvernightPerformance::isStopHit),
                failureTrend("TARGET_AND_STOP", baseline, recent, row -> row.isTargetHit() && row.isStopHit()),
                failureTrend("DRAWDOWN_3_PERCENT", baseline, recent, row -> row.getMaxDrawdownRate() != null
                        && row.getMaxDrawdownRate().compareTo(new BigDecimal("-3")) <= 0));
    }

    private FailureTrend failureTrend(String code, List<OvernightPerformance> baseline,
            List<OvernightPerformance> recent, java.util.function.Predicate<OvernightPerformance> predicate) {
        BigDecimal baselineRate = rate((int) baseline.stream().filter(predicate).count(), baseline.size());
        BigDecimal recentRate = rate((int) recent.stream().filter(predicate).count(), recent.size());
        return new FailureTrend(code, baselineRate, recentRate, subtract(recentRate, baselineRate));
    }

    private boolean lower(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) < 0;
    }

    private BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.subtract(right).setScale(6, RoundingMode.HALF_UP);
    }

    private OosValidation oosValidation(List<OvernightPerformance> rows, List<String> warnings) {
        List<LocalDate> dates = rows.stream().map(row -> row.getRecommendation().getRecommendationDate())
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (dates.size() < 2) {
            warnings.add("서로 다른 공식 추천일이 2일 미만이라 시간순 OOS 검증을 계산할 수 없습니다.");
            return OosValidation.empty();
        }
        int developmentDates = Math.min(dates.size() - 1, Math.max(1, (int) Math.floor(dates.size() * 0.7)));
        LocalDate splitDate = dates.get(developmentDates);
        List<OvernightPerformance> development = rows.stream()
                .filter(row -> row.getRecommendation().getRecommendationDate().isBefore(splitDate)).toList();
        List<OvernightPerformance> validation = rows.stream()
                .filter(row -> !row.getRecommendation().getRecommendationDate().isBefore(splitDate)).toList();
        String status = development.size() >= 20 && validation.size() >= 10 ? "READY" : "INSUFFICIENT_SAMPLE";
        if (!"READY".equals(status))
            warnings.add("OOS 구간은 개발 20건·검증 10건 미만이므로 방향 확인용이며 전략 채택 근거가 아닙니다.");
        BigDecimal scoreMedian = median(development.stream()
                .map(row -> row.getRecommendation().getRecommendationScore()).sorted().toList());
        return new OosValidation(status, "TIME_ORDERED_70_30_BY_RECOMMENDATION_DATE", splitDate,
                period(development), period(validation), scoreMedian,
                comparison(development, validation,
                        row -> row.getRecommendation().getRecommendationScore(), scoreMedian),
                featureComparisons(development, validation));
    }

    private PeriodSummary period(List<OvernightPerformance> rows) {
        List<LocalDate> dates = rows.stream().map(row -> row.getRecommendation().getRecommendationDate()).sorted().toList();
        int hits = (int) rows.stream().filter(OvernightPerformance::isTargetHit).count();
        return new PeriodSummary(dates.isEmpty() ? null : dates.getFirst(), dates.isEmpty() ? null : dates.getLast(),
                rows.size(), rate(hits, rows.size()), average(rows, OvernightPerformance::getCloseReturnRate));
    }

    private List<FeatureValidation> featureComparisons(List<OvernightPerformance> development,
            List<OvernightPerformance> validation) {
        List<FeatureValidation> result = new ArrayList<>();
        for (String feature : VALIDATED_FEATURES) {
            List<BigDecimal> values = development.stream().map(row -> featureValue(row, feature))
                    .filter(Objects::nonNull).sorted().toList();
            if (values.isEmpty()) continue;
            BigDecimal threshold = median(values);
            result.add(new FeatureValidation(feature, threshold,
                    comparison(development, validation, row -> featureValue(row, feature), threshold)));
        }
        return List.copyOf(result);
    }

    private Comparison comparison(List<OvernightPerformance> development, List<OvernightPerformance> validation,
            Function<OvernightPerformance, BigDecimal> value, BigDecimal threshold) {
        return new Comparison(metrics(development, value, threshold, false), metrics(development, value, threshold, true),
                metrics(validation, value, threshold, false), metrics(validation, value, threshold, true));
    }

    private GroupMetrics metrics(List<OvernightPerformance> rows, Function<OvernightPerformance, BigDecimal> value,
            BigDecimal threshold, boolean high) {
        List<OvernightPerformance> selected = rows.stream().filter(row -> value.apply(row) != null)
                .filter(row -> (value.apply(row).compareTo(threshold) >= 0) == high).toList();
        int hits = (int) selected.stream().filter(OvernightPerformance::isTargetHit).count();
        return new GroupMetrics(selected.size(), rate(hits, selected.size()),
                average(selected, OvernightPerformance::getCloseReturnRate));
    }

    private BigDecimal featureValue(OvernightPerformance row, String feature) {
        try {
            JsonNode value = mapper.readTree(row.getRecommendation().getFeatureSnapshot()).path(feature);
            return value.isNumber() || value.isTextual() && !value.asText().isBlank()
                    ? new BigDecimal(value.asText()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal median(List<BigDecimal> values) {
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) return values.get(middle);
        return values.get(middle - 1).add(values.get(middle)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    private List<ScoreBand> scoreBands(List<OvernightPerformance> rows) {
        return rows.stream().collect(Collectors.groupingBy(row -> scoreFloor(row.getRecommendation())))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<OvernightPerformance> values = entry.getValue();
                    int hits = (int) values.stream().filter(OvernightPerformance::isTargetHit).count();
                    Interval interval = wilson(hits, values.size());
                    return new ScoreBand(entry.getKey() + "-" + Math.min(100, entry.getKey() + 9), values.size(), hits,
                            rate(hits, values.size()), interval.lower(), interval.upper(),
                            average(values, OvernightPerformance::getCloseReturnRate),
                            average(values, OvernightPerformance::getMaxReturnRate),
                            average(values, OvernightPerformance::getMaxDrawdownRate));
                }).toList();
    }

    private List<SegmentSummary> segments(List<OvernightPerformance> rows) {
        List<SegmentSummary> result = new ArrayList<>();
        result.addAll(segment(rows, "시장", row -> row.getRecommendation().getStock() == null
                || row.getRecommendation().getStock().getMarket() == null ? "UNKNOWN"
                : row.getRecommendation().getStock().getMarket().name()));
        result.addAll(segment(rows, "후보 출처", row -> row.getRecommendation().getCandidateSource().name()));
        result.addAll(segment(rows, "탐지 유형", row -> row.getRecommendation().getScannerType() == null
                ? "BROAD" : row.getRecommendation().getScannerType().name()));
        return List.copyOf(result);
    }

    private List<SegmentSummary> segment(List<OvernightPerformance> rows, String dimension,
            Function<OvernightPerformance, String> classifier) {
        return rows.stream().collect(Collectors.groupingBy(classifier)).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(entry -> {
                    List<OvernightPerformance> values = entry.getValue();
                    int hits = (int) values.stream().filter(OvernightPerformance::isTargetHit).count();
                    return new SegmentSummary(dimension, entry.getKey(), values.size(), rate(hits, values.size()),
                            average(values, OvernightPerformance::getCloseReturnRate));
                }).toList();
    }

    private List<LossPattern> lossPatterns(List<OvernightPerformance> rows) {
        return List.of(
                loss("GAP_DOWN", rows, row -> negative(row.getOpenReturnRate())),
                loss("CLOSE_LOSS", rows, row -> negative(row.getCloseReturnRate())),
                loss("STOP_HIT", rows, OvernightPerformance::isStopHit),
                loss("TARGET_AND_STOP", rows, row -> row.isTargetHit() && row.isStopHit()),
                loss("DRAWDOWN_3_PERCENT", rows, row -> row.getMaxDrawdownRate() != null
                        && row.getMaxDrawdownRate().compareTo(new BigDecimal("-3")) <= 0));
    }

    private LossPattern loss(String code, List<OvernightPerformance> rows,
            java.util.function.Predicate<OvernightPerformance> predicate) {
        int count = (int) rows.stream().filter(predicate).count();
        return new LossPattern(code, count, rate(count, rows.size()));
    }

    private int scoreFloor(ClosingRecommendation recommendation) {
        int score = recommendation.getRecommendationScore().setScale(0, RoundingMode.FLOOR).intValue();
        return Math.max(0, Math.min(100, score)) / 10 * 10;
    }

    private BigDecimal average(List<OvernightPerformance> rows,
            Function<OvernightPerformance, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(int count, int total) {
        if (total == 0)
            return null;
        return BigDecimal.valueOf(count).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    private Interval wilson(int success, int total) {
        if (total == 0)
            return new Interval(null, null);
        double z = 1.96;
        double p = (double) success / total;
        double denominator = 1 + z * z / total;
        double center = (p + z * z / (2 * total)) / denominator;
        double margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator;
        return new Interval(percent(Math.max(0, center - margin)), percent(Math.min(1, center + margin)));
    }

    private BigDecimal percent(double value) {
        return BigDecimal.valueOf(value * 100).setScale(6, RoundingMode.HALF_UP);
    }

    private boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    public record StrategyAnalyticsReport(LocalDate from, LocalDate to, Instant generatedAt, int sampleSize,
            String population, List<BigDecimal> targetRates, List<String> warnings, List<ScoreBand> scoreBands,
            List<SegmentSummary> segments, List<LossPattern> lossPatterns, OosValidation oosValidation,
            PerformanceMonitoring monitoring, ContextCoverage contextCoverage,
            StrategyPromotionGate promotionGate) { }

    public record ScoreBand(String band, int sampleSize, int targetHits, BigDecimal targetHitRate,
            BigDecimal confidenceLower95, BigDecimal confidenceUpper95, BigDecimal averageCloseReturn,
            BigDecimal averageMaxReturn, BigDecimal averageMaxDrawdown) { }

    public record SegmentSummary(String dimension, String value, int sampleSize, BigDecimal targetHitRate,
            BigDecimal averageCloseReturn) { }

    public record LossPattern(String code, int count, BigDecimal rate) { }

    public record OosValidation(String status, String method, LocalDate splitDate,
            PeriodSummary development, PeriodSummary validation, BigDecimal developmentScoreMedian,
            Comparison scoreComparison, List<FeatureValidation> features) {
        private static OosValidation empty() {
            PeriodSummary empty = new PeriodSummary(null, null, 0, null, null);
            GroupMetrics group = new GroupMetrics(0, null, null);
            return new OosValidation("INSUFFICIENT_SAMPLE", "TIME_ORDERED_70_30_BY_RECOMMENDATION_DATE", null,
                    empty, empty, null, new Comparison(group, group, group, group), List.of());
        }
    }

    public record PeriodSummary(LocalDate from, LocalDate to, int sampleSize,
            BigDecimal targetHitRate, BigDecimal averageCloseReturn) { }

    public record Comparison(GroupMetrics developmentLow, GroupMetrics developmentHigh,
            GroupMetrics validationLow, GroupMetrics validationHigh) { }

    public record GroupMetrics(int sampleSize, BigDecimal targetHitRate, BigDecimal averageCloseReturn) { }

    public record FeatureValidation(String feature, BigDecimal developmentMedian, Comparison comparison) { }

    public record PerformanceMonitoring(String status, int baselineRecommendationDays, int recentRecommendationDays,
            PeriodSummary baselinePeriod, PeriodSummary recentPeriod, MonitoringMetrics baseline,
            MonitoringMetrics recent, BigDecimal closeReturnDelta, BigDecimal targetHitRateDelta,
            List<FailureTrend> failureTrends) {
        private static PerformanceMonitoring empty() {
            PeriodSummary period = new PeriodSummary(null, null, 0, null, null);
            MonitoringMetrics metrics = new MonitoringMetrics(0, null, null, null, null);
            return new PerformanceMonitoring("INSUFFICIENT_SAMPLE", BASELINE_RECOMMENDATION_DAYS,
                    RECENT_RECOMMENDATION_DAYS, period, period, metrics, metrics, null, null, List.of());
        }
    }

    public record MonitoringMetrics(int sampleSize, BigDecimal targetHitRate, BigDecimal stopHitRate,
            BigDecimal averageCloseReturn, BigDecimal averageMaxDrawdown) { }

    public record FailureTrend(String code, BigDecimal baselineRate, BigDecimal recentRate, BigDecimal rateDelta) { }

    public record ContextCoverage(boolean marketSegmentAvailable, boolean marketRegimeAvailable,
            boolean sectorHistoryAvailable, String note) { }

    public record StrategyPromotionGate(String currentStrategy, String recommendation,
            String statisticalModelStatus, String eventModelStatus, boolean productionActivationAllowed,
            List<ReadinessCheck> checks) { }

    public record ReadinessCheck(String code, String label, boolean passed, String requirement) { }

    private record Interval(BigDecimal lower, BigDecimal upper) { }
}
