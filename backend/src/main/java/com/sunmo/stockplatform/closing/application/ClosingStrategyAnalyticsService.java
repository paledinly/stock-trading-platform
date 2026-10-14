/*
 * 현재 전략 버전의 공식 FORWARD 추천 중 다음 거래일 성과가 완료된 표본만 모아 전략 분석 보고서를 만들어요.
 * 점수 구간별 성과, 시장·출처·탐지 유형별 성과, 손실 패턴, 시간순 OOS(표본 외 검증), 최근 성과 악화 감시, 전략 승격 관문을 담아요.
 * ClosingRecommendationController의 전략 분석 API가 써요.
 */
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

/**
 * 완료된 공식 추천 성과로 전략 상태를 진단하는 분석 서비스예요.
 * 지난 경기 기록만 모아 "점수가 높았던 선수가 실제로 잘했나, 요즘 폼이 떨어졌나, 정식 선발 기준을 채웠나"를 따져 보는 분석관이에요.
 * 표본이 적으면 경고를 붙이고, 어떤 경우에도 실운영 전환을 허용하지 않아요(productionActivationAllowed=false).
 */
@Service
public class ClosingStrategyAnalyticsService {
    // 퍼센트 배수, 최근 감시 구간(추천일 7일), 비교 기준 구간(추천일 20일)이에요.
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int RECENT_RECOMMENDATION_DAYS = 7;
    private static final int BASELINE_RECOMMENDATION_DAYS = 20;
    // OOS에서 중앙값으로 나눠 비교해 볼 Feature 이름들이에요.
    private static final List<String> VALIDATED_FEATURES = List.of(
            "vwapDistanceRate", "dayHighDistanceRate", "tradeStrength", "turnoverRatio");
    private final OvernightPerformanceRepository performances;
    private final ClosingTradingCalendar calendar;
    private final ObjectMapper mapper;

    /**
     * 공식 성과 저장소, 거래일 달력, JSON 변환기를 받아요.
     */
    public ClosingStrategyAnalyticsService(OvernightPerformanceRepository performances,
            ClosingTradingCalendar calendar, ObjectMapper mapper) {
        this.performances = performances;
        this.calendar = calendar;
        this.mapper = mapper;
    }

    /**
     * 기간 안의 공식 완료 성과로 전략 분석 보고서를 만들어요.
     * 받는 것: 시작일(없으면 종료일 90일 전), 종료일(없으면 오늘). 기간은 최대 730일이에요.
     * 돌려주는 것: 표본 수, 경고, 점수 구간, 구간별 성과, 손실 패턴, OOS, 감시, 맥락 범위, 승격 관문이 담긴 보고서.
     */
    @Transactional(readOnly = true)
    public StrategyAnalyticsReport analyze(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? calendar.today() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end) || start.isBefore(end.minusDays(730)))
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "분석 기간은 종료일 이전이며 최대 730일이어야 합니다");
        // 준비: 공식 완료 성과 중 현재 전략 버전 표본만 남겨요. 이전 버전의 평가 시각·진입 모델과 섞지 않아요.
        // 참고: 여기 표본은 최종 추천(SELECTED)의 공식 성과예요. ADR-010의 1차 표본(정밀 후보 전체 관측)과는 다른 집합이에요.
        List<OvernightPerformance> rows = performances.findOfficialCompletedBetween(start, end,
                OvernightPerformance.OBSERVATION_VERSION).stream()
                .filter(row -> ClosingRecommendation.STRATEGY_VERSION.equals(row.getRecommendation().getStrategyVersion())).toList();
        Set<BigDecimal> targetRates = rows.stream().map(OvernightPerformance::getTargetRate)
                .filter(Objects::nonNull).map(BigDecimal::stripTrailingZeros)
                .collect(Collectors.toCollection(TreeSet::new));
        // 해석 경고를 먼저 쌓아요. 점수는 확률이 아니고, 비용 반영 표본이 없거나 30건 미만이면 탐색용이라고 알려요.
        List<String> warnings = new ArrayList<>();
        warnings.add("추천 점수는 상승 확률이 아니며 규칙 기반 우선순위입니다.");
        warnings.add("현재 전략 " + ClosingRecommendation.STRATEGY_VERSION + " 표본만 포함합니다. 이전 평가 시각·진입 모델과 합산하지 않습니다.");
        List<OvernightPerformance> netRows = rows.stream().filter(row -> row.getNetReturnRate() != null).toList();
        if (netRows.isEmpty())
            warnings.add("검증된 비용 설정이 적용된 공식 가상체결 순수익 표본이 없습니다.");
        if (rows.size() < 30)
            warnings.add("공식 전진 표본이 30건 미만이므로 결과는 탐색적으로만 해석하세요.");
        if (targetRates.size() > 1)
            warnings.add("서로 다른 목표수익률이 포함되어 점수 구간을 직접 비교하기 어렵습니다.");
        // 처리: OOS·감시·맥락 범위를 계산하고, 결과: 구간별 집계와 승격 관문을 묶어 보고서로 돌려줘요.
        OosValidation oos = oosValidation(rows, warnings);
        PerformanceMonitoring monitoring = monitoring(rows, warnings);
        ContextCoverage context = new ContextCoverage(true, false, false,
                "시장 구분 성과는 제공하지만 시점별 지수 Regime과 업종 이력 데이터는 아직 없습니다.");
        return new StrategyAnalyticsReport(start, end, calendar.now(), rows.size(),
                "OFFICIAL_FORWARD_POINT_IN_TIME", netRows.size(), average(netRows, OvernightPerformance::getNetReturnRate),
                List.copyOf(targetRates), List.copyOf(warnings),
                scoreBands(rows), segments(rows), lossPatterns(rows), oos, monitoring, context,
                promotionGate(oos, monitoring, context, netRows.size()));
    }

    /**
     * 규칙 기반 전략을 다음 단계 실험으로 올릴 수 있는지 점검표로 판정해요.
     * 받는 것: OOS 결과, 최근 감시 결과, 맥락 범위, 비용 반영 표본 수.
     * 돌려주는 것: 통계 모델·이벤트 모델 각각 READY_FOR_EXPERIMENT 또는 BLOCKED, 점검 항목 목록. 실운영 전환은 항상 false예요.
     */
    private StrategyPromotionGate promotionGate(OosValidation oos, PerformanceMonitoring monitoring,
            ContextCoverage context, int netSampleSize) {
        List<ReadinessCheck> checks = List.of(
                new ReadinessCheck("OFFICIAL_OOS", "공식 시간순 표본 외 검증", "READY".equals(oos.status()),
                        "개발·검증 구간의 최소 표본과 시점 분리가 필요합니다."),
                new ReadinessCheck("RECENT_STABILITY", "최근 전략 안정성", "OBSERVE".equals(monitoring.status()),
                        "최근 성과 감시가 준비되고 악화 상태가 아니어야 합니다."),
                new ReadinessCheck("NET_EXECUTION_OUTCOMES", "비용 반영 공식 모의체결 성과", netSampleSize >= 30,
                        "검증된 비용과 진입가격이 있는 공식 가상체결 표본이 최소 30건 필요합니다. 현재 "
                                + netSampleSize + "건입니다."),
                new ReadinessCheck("MARKET_CONTEXT", "시점별 시장·업종 맥락", context.marketRegimeAvailable()
                        && context.sectorHistoryAvailable(), "지수 원시값과 유효기간이 있는 업종 이력이 필요합니다."),
                new ReadinessCheck("TIMESTAMPED_EVENTS", "공개·수신 시각이 보존된 뉴스·공시", false,
                        "검증 가능한 이벤트 데이터 원천과 정정 이력이 없습니다."));
        boolean statisticalReady = checks.stream().filter(check -> !check.code().equals("TIMESTAMPED_EVENTS"))
                .allMatch(ReadinessCheck::passed);
        boolean eventReady = checks.stream().allMatch(ReadinessCheck::passed);
        // ⚠️ 확인 필요: 현재 전략 라벨이 "RULE_BASED_V9_1515"로 고정돼 있어 STRATEGY_VERSION(v9_1)과 이름이 어긋나요.
        return new StrategyPromotionGate("RULE_BASED_V9_1515", "KEEP_BASELINE",
                statisticalReady ? "READY_FOR_EXPERIMENT" : "BLOCKED",
                eventReady ? "READY_FOR_EXPERIMENT" : "BLOCKED", false, List.copyOf(checks));
    }

    /**
     * 최근 7개 추천일과 그 앞 최대 20개 추천일을 비교해 성과가 나빠졌는지 감시해요.
     * 받는 것: 성과 표본, 경고 목록(여기에 경고를 추가해요).
     * 돌려주는 것: 상태(INSUFFICIENT_SAMPLE/OBSERVE/DEGRADED), 두 구간의 지표와 차이, 실패 유형 추세.
     */
    private PerformanceMonitoring monitoring(List<OvernightPerformance> rows, List<String> warnings) {
        List<LocalDate> dates = rows.stream().map(row -> row.getRecommendation().getRecommendationDate())
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (dates.size() <= RECENT_RECOMMENDATION_DAYS) {
            warnings.add("최근 성과 악화 비교에 필요한 추천일이 부족합니다.");
            return PerformanceMonitoring.empty();
        }
        // 처리: 날짜를 최근 구간과 기준 구간으로 나눠 각 표본의 지표를 계산해요.
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
        // 기준 구간 10일 이상일 때만 판정해요. 최근 평균 종가수익이 음수이고, 종가수익과 목표 도달률이 모두 기준보다 낮으면 DEGRADED예요.
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

    /**
     * 표본의 감시 지표를 계산해요.
     * 받는 것: 성과 표본. 돌려주는 것: 표본 수, 목표·손절 도달률(%), 평균 종가수익, 평균 최대 낙폭.
     */
    private MonitoringMetrics monitoringMetrics(List<OvernightPerformance> rows) {
        int targets = (int) rows.stream().filter(OvernightPerformance::isTargetHit).count();
        int stops = (int) rows.stream().filter(OvernightPerformance::isStopHit).count();
        return new MonitoringMetrics(rows.size(), rate(targets, rows.size()), rate(stops, rows.size()),
                average(rows, OvernightPerformance::getCloseReturnRate),
                average(rows, OvernightPerformance::getMaxDrawdownRate));
    }

    /**
     * 실패 유형(갭 하락, 종가 손실, 손절, 목표·손절 동시, -3% 낙폭)별로 기준·최근 비율을 비교해요.
     * 받는 것: 기준 구간 표본, 최근 구간 표본. 돌려주는 것: 유형별 추세 목록.
     */
    private List<FailureTrend> failureTrends(List<OvernightPerformance> baseline, List<OvernightPerformance> recent) {
        return List.of(
                failureTrend("GAP_DOWN", baseline, recent, row -> negative(row.getOpenReturnRate())),
                failureTrend("CLOSE_LOSS", baseline, recent, row -> negative(row.getCloseReturnRate())),
                failureTrend("STOP_HIT", baseline, recent, OvernightPerformance::isStopHit),
                failureTrend("TARGET_AND_STOP", baseline, recent, row -> row.isTargetHit() && row.isStopHit()),
                failureTrend("DRAWDOWN_3_PERCENT", baseline, recent, row -> row.getMaxDrawdownRate() != null
                        && row.getMaxDrawdownRate().compareTo(new BigDecimal("-3")) <= 0));
    }

    /**
     * 실패 유형 1개의 기준·최근 비율과 차이를 계산해요.
     * 받는 것: 유형 코드, 두 구간 표본, 해당 여부 판정식. 돌려주는 것: FailureTrend.
     */
    private FailureTrend failureTrend(String code, List<OvernightPerformance> baseline,
            List<OvernightPerformance> recent, java.util.function.Predicate<OvernightPerformance> predicate) {
        BigDecimal baselineRate = rate((int) baseline.stream().filter(predicate).count(), baseline.size());
        BigDecimal recentRate = rate((int) recent.stream().filter(predicate).count(), recent.size());
        return new FailureTrend(code, baselineRate, recentRate, subtract(recentRate, baselineRate));
    }

    /**
     * 왼쪽 값이 오른쪽보다 작은지 확인해요. 둘 중 하나라도 없으면 false예요.
     */
    private boolean lower(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) < 0;
    }

    /**
     * 두 값의 차이를 계산해요. 하나라도 없으면 null이에요.
     */
    private BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.subtract(right).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 추천일 기준 앞 70%는 개발 구간, 뒤 30%는 검증 구간으로 나눠 시간순 OOS(표본 외 검증)를 계산해요.
     * 받는 것: 성과 표본, 경고 목록. 돌려주는 것: 개발 20건·검증 10건 이상이면 READY, 아니면 INSUFFICIENT_SAMPLE인 결과.
     * 개발 구간의 점수·Feature 중앙값을 기준으로 상·하위 그룹 성과를 두 구간에서 비교해요.
     */
    private OosValidation oosValidation(List<OvernightPerformance> rows, List<String> warnings) {
        List<LocalDate> dates = rows.stream().map(row -> row.getRecommendation().getRecommendationDate())
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (dates.size() < 2) {
            warnings.add("서로 다른 공식 추천일이 2일 미만이라 시간순 OOS 검증을 계산할 수 없습니다.");
            return OosValidation.empty();
        }
        // 분할 날짜: 최소 1일은 개발 구간에, 최소 1일은 검증 구간에 남겨요. 미래 구간 정보를 개발 구간에 쓰지 않도록 시간순으로만 나눠요.
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

    /**
     * 표본의 기간 요약(첫·마지막 추천일, 표본 수, 목표 도달률, 평균 종가수익)을 만들어요.
     * 받는 것: 성과 표본. 돌려주는 것: PeriodSummary.
     */
    private PeriodSummary period(List<OvernightPerformance> rows) {
        List<LocalDate> dates = rows.stream().map(row -> row.getRecommendation().getRecommendationDate()).sorted().toList();
        int hits = (int) rows.stream().filter(OvernightPerformance::isTargetHit).count();
        return new PeriodSummary(dates.isEmpty() ? null : dates.getFirst(), dates.isEmpty() ? null : dates.getLast(),
                rows.size(), rate(hits, rows.size()), average(rows, OvernightPerformance::getCloseReturnRate));
    }

    /**
     * Feature마다 개발 구간 중앙값으로 상·하위를 나눠 두 구간의 성과를 비교해요.
     * 받는 것: 개발·검증 표본. 돌려주는 것: 값이 있는 Feature의 비교 결과 목록.
     */
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

    /**
     * 기준값 이상(상위)·미만(하위) 그룹의 성과를 개발·검증 구간 각각 계산해요.
     * 받는 것: 개발·검증 표본, 값을 꺼내는 함수, 기준값. 돌려주는 것: 네 그룹 지표를 담은 Comparison.
     */
    private Comparison comparison(List<OvernightPerformance> development, List<OvernightPerformance> validation,
            Function<OvernightPerformance, BigDecimal> value, BigDecimal threshold) {
        return new Comparison(metrics(development, value, threshold, false), metrics(development, value, threshold, true),
                metrics(validation, value, threshold, false), metrics(validation, value, threshold, true));
    }

    /**
     * 기준값으로 나눈 한쪽 그룹의 표본 수, 목표 도달률, 평균 종가수익을 계산해요.
     * 받는 것: 표본, 값 함수, 기준값, 상위 그룹 여부. 돌려주는 것: GroupMetrics(값이 없는 행은 빼요).
     */
    private GroupMetrics metrics(List<OvernightPerformance> rows, Function<OvernightPerformance, BigDecimal> value,
            BigDecimal threshold, boolean high) {
        List<OvernightPerformance> selected = rows.stream().filter(row -> value.apply(row) != null)
                .filter(row -> (value.apply(row).compareTo(threshold) >= 0) == high).toList();
        int hits = (int) selected.stream().filter(OvernightPerformance::isTargetHit).count();
        return new GroupMetrics(selected.size(), rate(hits, selected.size()),
                average(selected, OvernightPerformance::getCloseReturnRate));
    }

    /**
     * 추천에 저장된 Feature 스냅샷에서 숫자 값을 꺼내요.
     * 받는 것: 성과 행, Feature 이름. 돌려주는 것: 숫자 값, 없거나 읽을 수 없으면 null.
     */
    private BigDecimal featureValue(OvernightPerformance row, String feature) {
        try {
            JsonNode value = mapper.readTree(row.getRecommendation().getFeatureSnapshot()).path(feature);
            return value.isNumber() || value.isTextual() && !value.asText().isBlank()
                    ? new BigDecimal(value.asText()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 정렬된 값 목록의 중앙값을 구해요(짝수 개면 가운데 두 값의 평균).
     * 받는 것: 정렬된 값 목록(비어 있지 않아야 해요). 돌려주는 것: 중앙값.
     */
    private BigDecimal median(List<BigDecimal> values) {
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) return values.get(middle);
        return values.get(middle - 1).add(values.get(middle)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    /**
     * 추천 점수를 10점 단위 구간으로 나눠 구간별 목표 도달률과 95% 신뢰구간, 평균 수익·낙폭을 계산해요.
     * 받는 것: 성과 표본. 돌려주는 것: 점수 구간 목록(낮은 구간부터).
     */
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

    /**
     * 시장, 후보 출처, 탐지 유형별로 성과를 나눠 봐요.
     * 받는 것: 성과 표본. 돌려주는 것: 구분별 요약 목록.
     */
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

    /**
     * 한 기준으로 표본을 묶어 그룹별 표본 수, 목표 도달률, 평균 종가수익을 계산해요.
     * 받는 것: 표본, 구분 이름, 분류 함수. 돌려주는 것: 그룹 이름순 요약 목록.
     */
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

    /**
     * 손실 패턴(갭 하락, 종가 손실, 손절, 목표·손절 동시, -3% 낙폭)별 건수와 비율을 계산해요.
     * 받는 것: 성과 표본. 돌려주는 것: 패턴 목록.
     */
    private List<LossPattern> lossPatterns(List<OvernightPerformance> rows) {
        return List.of(
                loss("GAP_DOWN", rows, row -> negative(row.getOpenReturnRate())),
                loss("CLOSE_LOSS", rows, row -> negative(row.getCloseReturnRate())),
                loss("STOP_HIT", rows, OvernightPerformance::isStopHit),
                loss("TARGET_AND_STOP", rows, row -> row.isTargetHit() && row.isStopHit()),
                loss("DRAWDOWN_3_PERCENT", rows, row -> row.getMaxDrawdownRate() != null
                        && row.getMaxDrawdownRate().compareTo(new BigDecimal("-3")) <= 0));
    }

    /**
     * 조건에 맞는 손실 패턴 1개의 건수와 비율을 계산해요.
     * 받는 것: 패턴 코드, 표본, 판정식. 돌려주는 것: LossPattern.
     */
    private LossPattern loss(String code, List<OvernightPerformance> rows,
            java.util.function.Predicate<OvernightPerformance> predicate) {
        int count = (int) rows.stream().filter(predicate).count();
        return new LossPattern(code, count, rate(count, rows.size()));
    }

    /**
     * 추천 점수를 10점 단위 아래쪽 경계로 내려요(예: 67.4 → 60). 0~100으로 잘라요.
     * 받는 것: 추천. 돌려주는 것: 구간 시작 점수.
     */
    private int scoreFloor(ClosingRecommendation recommendation) {
        int score = recommendation.getRecommendationScore().setScale(0, RoundingMode.FLOOR).intValue();
        return Math.max(0, Math.min(100, score)) / 10 * 10;
    }

    /**
     * 값이 있는 행만 골라 평균을 내요.
     * 받는 것: 표본, 값 함수. 돌려주는 것: 평균(소수 6자리), 값이 없으면 null.
     */
    private BigDecimal average(List<OvernightPerformance> rows,
            Function<OvernightPerformance, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * 비율(%)을 계산해요.
     * 받는 것: 해당 건수, 전체 건수. 돌려주는 것: 퍼센트, 전체가 0이면 null.
     */
    private BigDecimal rate(int count, int total) {
        if (total == 0)
            return null;
        return BigDecimal.valueOf(count).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /**
     * 성공률의 95% 윌슨 신뢰구간(표본이 적을 때도 덜 치우치는 신뢰구간 계산법)을 구해요.
     * 받는 것: 성공 수, 전체 수. 돌려주는 것: 하한·상한(%), 표본이 없으면 둘 다 null.
     */
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

    /**
     * 소수 비율을 퍼센트(소수 6자리)로 바꿔요.
     */
    private BigDecimal percent(double value) {
        return BigDecimal.valueOf(value * 100).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 값이 음수인지 확인해요. 없으면 false예요.
     */
    private boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    // 아래는 API로 내보내는 보고서 모양이에요.
    /** 전략 분석 보고서 전체예요. 기간, 생성 시각, 표본 수와 모집단, 비용 반영 표본, 경고, 각 분석 결과를 담아요. */
    public record StrategyAnalyticsReport(LocalDate from, LocalDate to, Instant generatedAt, int sampleSize,
            String population, int netExecutionSampleSize, BigDecimal averageNetReturn,
            List<BigDecimal> targetRates, List<String> warnings, List<ScoreBand> scoreBands,
            List<SegmentSummary> segments, List<LossPattern> lossPatterns, OosValidation oosValidation,
            PerformanceMonitoring monitoring, ContextCoverage contextCoverage,
            StrategyPromotionGate promotionGate) { }

    /** 10점 단위 점수 구간의 표본 수, 목표 도달 수·률, 95% 신뢰구간, 평균 종가·최대 수익, 평균 최대 낙폭이에요. */
    public record ScoreBand(String band, int sampleSize, int targetHits, BigDecimal targetHitRate,
            BigDecimal confidenceLower95, BigDecimal confidenceUpper95, BigDecimal averageCloseReturn,
            BigDecimal averageMaxReturn, BigDecimal averageMaxDrawdown) { }

    /** 한 구분값(예: 시장=KOSPI)의 표본 수, 목표 도달률, 평균 종가수익이에요. */
    public record SegmentSummary(String dimension, String value, int sampleSize, BigDecimal targetHitRate,
            BigDecimal averageCloseReturn) { }

    /** 손실 패턴 1개의 코드, 건수, 비율(%)이에요. */
    public record LossPattern(String code, int count, BigDecimal rate) { }

    /** 시간순 OOS 결과예요. 분할 날짜, 두 구간 요약, 개발 구간 점수 중앙값, 점수·Feature별 비교를 담아요. empty()는 표본 부족용이에요. */
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

    /** 기간 요약(첫·마지막 추천일, 표본 수, 목표 도달률, 평균 종가수익)이에요. */
    public record PeriodSummary(LocalDate from, LocalDate to, int sampleSize,
            BigDecimal targetHitRate, BigDecimal averageCloseReturn) { }

    /** 기준값 하위·상위 그룹 성과를 개발·검증 구간별로 담은 비교예요. */
    public record Comparison(GroupMetrics developmentLow, GroupMetrics developmentHigh,
            GroupMetrics validationLow, GroupMetrics validationHigh) { }

    /** 한 그룹의 표본 수, 목표 도달률, 평균 종가수익이에요. */
    public record GroupMetrics(int sampleSize, BigDecimal targetHitRate, BigDecimal averageCloseReturn) { }

    /** Feature 1개의 개발 구간 중앙값과 그 기준의 비교 결과예요. */
    public record FeatureValidation(String feature, BigDecimal developmentMedian, Comparison comparison) { }

    /** 최근 성과 감시 결과예요. 상태, 구간 길이, 두 구간 요약·지표, 차이, 실패 유형 추세를 담아요. empty()는 표본 부족용이에요. */
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

    /** 감시 지표(표본 수, 목표·손절 도달률, 평균 종가수익, 평균 최대 낙폭)예요. */
    public record MonitoringMetrics(int sampleSize, BigDecimal targetHitRate, BigDecimal stopHitRate,
            BigDecimal averageCloseReturn, BigDecimal averageMaxDrawdown) { }

    /** 실패 유형 1개의 기준·최근 비율과 차이예요. */
    public record FailureTrend(String code, BigDecimal baselineRate, BigDecimal recentRate, BigDecimal rateDelta) { }

    /** 분석에 쓸 수 있는 맥락 데이터 범위예요. 지금은 시장 구분만 있고 지수 Regime(시장 국면)과 업종 이력은 없어요. */
    public record ContextCoverage(boolean marketSegmentAvailable, boolean marketRegimeAvailable,
            boolean sectorHistoryAvailable, String note) { }

    /** 전략 승격 관문(다음 단계로 넘어가기 전 통과해야 하는 조건) 결과예요. 실운영 허용 여부는 항상 false예요. */
    public record StrategyPromotionGate(String currentStrategy, String recommendation,
            String statisticalModelStatus, String eventModelStatus, boolean productionActivationAllowed,
            List<ReadinessCheck> checks) { }

    /** 점검 항목 1개의 코드, 이름, 통과 여부, 요구 조건이에요. */
    public record ReadinessCheck(String code, String label, boolean passed, String requirement) { }

    /** 신뢰구간 하한·상한(%)이에요. */
    private record Interval(BigDecimal lower, BigDecimal upper) { }
}
