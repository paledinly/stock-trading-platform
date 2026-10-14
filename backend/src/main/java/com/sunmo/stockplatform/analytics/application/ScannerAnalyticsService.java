/*
 * 스캐너 감지들의 사후 성과를 모아 통계(승률, 평균 수익률, 목표가·손절가 도달, 시간대별·신호 조합별 성과)를 만드는 파일이에요.
 * 결과는 과거 감지의 기록일 뿐이고 앞으로의 상승 확률이나 검증된 수익성을 뜻하지 않아요.
 * ScannerAnalyticsController가 화면 요청을 받아 이 서비스를 불러요.
 */
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

/**
 * 감지 성과 기록을 읽어서 한 장짜리 성적표로 요약해 주는 읽기 전용 서비스예요.
 * 학생들 시험 점수를 모아 평균, 합격률, 시간대·과목별 성적을 정리해 주는 성적 담당 선생님 같은 역할이에요.
 * 같은 조건 요청은 짧은 캐시(자주 쓰는 결과를 잠깐 보관해 두는 곳)에서 바로 돌려줘요.
 */
@Service
@Transactional(readOnly = true)
public class ScannerAnalyticsService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final ScannerDetectionRepository detections;
    private final DetectionPerformanceRepository performances;
    private final SummaryReadCache cache;

    /**
     * 캐시 없이(보관 시간 0) 서비스를 만들어요. 주로 테스트에서 쓰는 것으로 보여요.
     * 받는 것: 감지 레포지토리(DB 조회 담당 객체), 성과 레포지토리. 돌려주는 것: 서비스 객체예요.
     */
    public ScannerAnalyticsService(ScannerDetectionRepository detections,
            DetectionPerformanceRepository performances) {
        this(detections, performances, new SummaryReadCache(java.time.Duration.ZERO));
    }

    /**
     * 스프링이 실제로 쓰는 생성자예요.
     * 받는 것: 감지 레포지토리, 성과 레포지토리, 요약 캐시. 돌려주는 것: 서비스 객체예요.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ScannerAnalyticsService(ScannerDetectionRepository detections,
            DetectionPerformanceRepository performances, SummaryReadCache cache) {
        this.detections = detections;
        this.performances = performances;
        this.cache = cache;
    }

    /**
     * 감지 한 건의 성과 상세를 찾아요.
     * 받는 것: 감지 ID. 돌려주는 것: 성과 응답이에요. 없으면 404 오류를 던져요.
     */
    public PerformanceResponse performance(long detectionId) {
        return performances.findById(detectionId)
                .map(PerformanceResponse::from)
                .orElseThrow(() -> new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.NOT_FOUND,
                        "Performance not found: " + detectionId));
    }

    /**
     * 기본 조건(목표 +3%, 손절 -2%, 최소 표본 20건)으로 통계를 만들어요.
     * 받는 것: 스캐너 설정 ID(없으면 전체), 조회 시작·끝 시각. 돌려주는 것: 통계 응답이에요.
     */
    public AnalyticsResponse analytics(Long settingId, Instant from, Instant to) {
        return analytics(settingId, from, to, bd("3"), bd("-2"), 20);
    }

    /**
     * 조건을 검사한 뒤 통계를 캐시에서 꺼내거나 새로 계산해요.
     * 받는 것: 설정 ID(없으면 전체), 기간(최대 93일), 목표 수익률(양수 %), 손절 수익률(음수 %), 최소 표본 수(1~10000).
     * 돌려주는 것: 통계 응답이에요. 조건이 잘못되면 400 오류를 던져요.
     */
    public AnalyticsResponse analytics(Long settingId, Instant from, Instant to, BigDecimal targetRate,
            BigDecimal stopRate, int minimumSampleSize) {
        if (from == null || to == null || from.isAfter(to)
                || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(93)) > 0
                || minimumSampleSize < 1 || minimumSampleSize > 10000
                || targetRate == null || targetRate.signum() <= 0 || stopRate == null || stopRate.signum() >= 0)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "Analytics requires an ordered range of at most 93 days, positive target, negative stop and valid sample size");
        // 3.0과 3처럼 값은 같고 표기만 다른 숫자가 같은 캐시 칸을 쓰도록 끝자리 0을 없앤 값을 키로 써요.
        return cache.get(new AnalyticsKey(settingId, from, to, targetRate.stripTrailingZeros(),
                stopRate.stripTrailingZeros(), minimumSampleSize),
                () -> calculate(settingId, from, to, targetRate, stopRate, minimumSampleSize));
    }

    /**
     * 통계 캐시의 열쇠(키)예요. 요청 조건이 모두 같아야 같은 결과를 다시 써요.
     * 사물함 번호처럼 조건 묶음 하나가 결과 칸 하나를 가리켜요.
     */
    private record AnalyticsKey(Long settingId, Instant from, Instant to, BigDecimal targetRate,
            BigDecimal stopRate, int minimumSampleSize) {}

    /**
     * DB에서 기간 안 성과를 읽어 실제 통계를 계산해요.
     * 받는 것: analytics와 같은 조건들. 돌려주는 것: 통계 응답이에요.
     */
    private AnalyticsResponse calculate(Long settingId, Instant from, Instant to, BigDecimal targetRate,
            BigDecimal stopRate, int minimumSampleSize) {
        // ⚠️ 확인 필요: 상태를 거르지 않아서 아직 추적 중(PENDING)이거나 DATA_MISSING인 기록도 평균·승률 계산에 섞여요.
        var rows = performances.findAnalyticsRows(settingId, from, to);
        long completed = rows.stream().filter(performance -> performance.getStatus() == PerformanceStatus.COMPLETED)
                .count();
        long missing = rows.stream().filter(performance -> performance.getStatus() == PerformanceStatus.DATA_MISSING)
                .count();
        // 계산 버전이 하나면 그 이름을, 여러 개 섞였으면 "mixed"를 보여 줘요.
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

    /**
     * 목표 수익률과 손절 수익률 중 어느 쪽에 닿았는지 세요.
     * 받는 것: 성과 행 목록, 목표(%), 손절(%). 돌려주는 것: 건수, 비율, 평균 마감 수익률(expectancy)이에요.
     * ⚠️ 확인 필요: 이름은 "먼저 닿음(first)"이지만 실제로는 최고·최저 수익률만 보고 순서는 알 수 없어요.
     * 둘 다 닿은 경우는 neither로 세져요.
     */
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

    /**
     * 감지 시각(서울 기준)의 "시" 단위로 묶어 시간대별 성과를 만들어요.
     * 받는 것: 성과 행 목록. 돌려주는 것: "09:00" 같은 시간대별 승률·평균 수익률 목록(시간순)이에요.
     */
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

    /**
     * 스캐너 종류 × 기회점수 구간 × 위험점수 구간 조합별로 성과를 묶어요.
     * 받는 것: 성과 행 목록, 최소 표본 수. 돌려주는 것: 조합별 승률·평균 수익률·신뢰도 목록이에요.
     */
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
                // ⚠️ 확인 필요: 신뢰도를 글자 순으로 정렬해서 HIGH → LOW → MEDIUM 순서가 돼요(의도는 HIGH → MEDIUM → LOW로 보여요).
                .sorted(Comparator.comparing(SignalCombinationResponse::confidence)
                        .thenComparing(SignalCombinationResponse::averageReturn,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * 기간 전체의 과거 성과 요약(표본 수, 승률, 평균 마감 수익률, 평균 MFE·MAE, 신뢰도)을 만들어요.
     * 받는 것: 성과 행 목록, 최소 표본 수. 돌려주는 것: 요약 응답이에요.
     * 참고: expectancy(기대값)는 지금 평균 마감 수익률과 같은 값이에요. 미래 수익을 보장하는 값이 아니에요.
     */
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

    /** 하는 일: 점수를 구간으로 나눠요. 70 이상 HIGH, 40 이상 MID, 그 아래 LOW, 없으면 UNKNOWN을 돌려줘요. */
    private String band(BigDecimal value) {
        if (value == null)
            return "UNKNOWN";
        if (value.compareTo(bd("70")) >= 0)
            return "HIGH";
        if (value.compareTo(bd("40")) >= 0)
            return "MID";
        return "LOW";
    }

    /**
     * 하는 일: 표본 수로 신뢰도를 정해요. 최소 표본 이상이면 HIGH, 그 절반(최소 5건) 이상이면 MEDIUM, 아니면 LOW예요.
     * 받는 것: 표본 수, 최소 표본 수. 돌려주는 것: 신뢰도 글자예요.
     */
    private String confidence(long sampleSize, int minimumSampleSize) {
        if (sampleSize >= minimumSampleSize)
            return "HIGH";
        if (sampleSize >= Math.max(5, minimumSampleSize / 2))
            return "MEDIUM";
        return "LOW";
    }

    /**
     * 하는 일: 고른 값(getter)의 평균을 소수 6자리까지 구해요. 빈 값(null)은 빼고 계산해요.
     * 받는 것: 행 목록, 값을 꺼낼 함수. 돌려주는 것: 평균, 값이 하나도 없으면 null이에요.
     */
    private BigDecimal avg(List<AnalyticsRow> rows, Function<AnalyticsRow, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 고른 수익률이 0보다 큰 비율(승률, %)을 구해요. 빈 값은 빼고 계산해요.
     * 받는 것: 행 목록, 값을 꺼낼 함수. 돌려주는 것: 승률, 값이 없으면 null이에요.
     */
    private BigDecimal win(List<AnalyticsRow> rows, Function<AnalyticsRow, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        long wins = values.stream().filter(value -> value.signum() > 0).count();
        return rate(wins, values.size());
    }

    /** 하는 일: count / total × 100 비율(%)을 구해요. total이 0이면 null을 돌려줘요. */
    private BigDecimal rate(long count, long total) {
        if (total == 0)
            return null;
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /** 하는 일: 글자로 된 숫자를 정확한 소수(BigDecimal)로 바꿔요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
