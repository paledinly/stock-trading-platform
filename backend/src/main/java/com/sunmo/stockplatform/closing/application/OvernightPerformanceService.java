/*
 * 마감 추천 종목을 다음 거래일 5분봉으로 따라가며 시가·종가·최고·최저 수익률과 목표/손절 도달 여부를 기록하는 서비스예요.
 * 다음날 봉이 빠짐없이 모였을 때만 완료(COMPLETED)로 확정하고, 공식 FORWARD 실행이면 가상 체결 결과도 함께 남겨요.
 * 스케줄러(ClosingRecommendationScheduler)와 추천 API(ClosingRecommendationController)가 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.*;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.common.error.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 추천 다음날의 성과를 기록하고 조회하는 클래스예요.
 * 오늘 심은 씨앗이 내일 얼마나 자랐는지 자로 재서 관찰 일지에 적는 사람과 같아요. 하루가 다 끝나 기록이 다 모여야 "관찰 완료" 도장을 찍어요.
 * 추천 점수가 상승 확률이 아니듯, 이 기록도 표본이 쌓이기 전에는 수익성 검증으로 보지 않아요.
 */
@Service
public class OvernightPerformanceService {
    private final ClosingRecommendationRepository recommendations;
    private final OvernightPerformanceRepository performances;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final ObjectMapper mapper;
    private final OvernightExecutionSimulator execution;
    private final ClosingPaperEntryService paperEntries;

    /**
     * 하는 일: 의존 객체를 받아 서비스를 만들어요.
     * 받는 것: 추천 저장소, 성과 저장소, 봉 저장소, 거래일 달력, JSON 변환기, 가상 체결 계산기, 가상 진입 서비스.
     * 돌려주는 것: 없음(생성자).
     */
    public OvernightPerformanceService(ClosingRecommendationRepository recommendations,
            OvernightPerformanceRepository performances, StockCandleRepository candles,
            ClosingTradingCalendar calendar, ObjectMapper mapper, OvernightExecutionSimulator execution, ClosingPaperEntryService paperEntries) {
        this.paperEntries = paperEntries;
        this.recommendations = recommendations;
        this.performances = performances;
        this.candles = candles;
        this.calendar = calendar;
        this.mapper = mapper;
        this.execution = execution;
    }

    /**
     * 하는 일: 추천일(또는 실행 ID)의 추천 종목마다 다음날 성과를 계산해 저장해요. 트랜잭션(여러 저장을 한 묶음으로 처리하는 단위) 안에서 돌아요.
     * 받는 것: 추천일(없으면 오늘의 직전 거래일), 목표 수익률(없으면 +3%), 손절 수익률(없으면 -2%), 실행 ID(있으면 날짜 대신 사용).
     * 돌려주는 것: 처리 건수, 완료 건수, 자료 부족 건수와 종목별 성과를 담은 응답.
     */
    @Transactional
    public TrackPerformanceResponse track(LocalDate date, BigDecimal targetRate, BigDecimal stopRate, Long runId) {
        // 준비: 기본값을 채우고 목표는 양수, 손절은 0 미만 -100 초과인지 검사해요.
        // 날짜 없이 부르면(API) ClosingTradingCalendar의 직전 거래일을 써요. 그래서 월요일·휴장일 다음날에도 추천이 있던 날을 가리켜요.
        LocalDate targetDate = date == null ? calendar.previousTradingDay(calendar.today()) : date;
        BigDecimal target = targetRate == null ? new BigDecimal("3") : targetRate;
        BigDecimal stop = stopRate == null ? new BigDecimal("-2") : stopRate;
        if (target.signum() <= 0 || stop.signum() >= 0 || stop.compareTo(new BigDecimal("-100")) <= 0)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Invalid target/stop rates");
        // 처리: 추천 행을 잠금 조회(findLockedById)해 동시에 두 곳에서 같은 성과를 고치지 않게 하고 하나씩 평가해요.
        List<ClosingRecommendation> rows = runId == null
                ? recommendations.findByRecommendationDateOrderByRankAsc(targetDate)
                : recommendations.findByRunIdOrderByRankAsc(runId);
        Instant now = calendar.now();
        List<OvernightPerformance> saved = rows.stream().map(row -> {
            ClosingRecommendation locked = recommendations.findLockedById(row.getId()).orElseThrow();
            return evaluate(locked, target, stop, now);
        }).toList();
        // 결과: 완료 건수는 현재 관측 버전(OBSERVATION_VERSION)으로 완료된 것만 세요.
        return new TrackPerformanceResponse(targetDate, now, rows.size(),
                (int) saved.stream().filter(p -> p.getStatus() == OvernightPerformanceStatus.COMPLETED
                        && OvernightPerformance.OBSERVATION_VERSION.equals(p.getCalculationVersion())).count(),
                (int) saved.stream().filter(p -> p.getStatus() == OvernightPerformanceStatus.DATA_INCOMPLETE
                        || p.getStatus() == OvernightPerformanceStatus.DATA_MISSING).count(),
                target, stop, OvernightPerformance.OBSERVATION_VERSION,
                saved.stream().map(OvernightPerformanceResponse::from).toList());
    }

    /**
     * 하는 일: 저장된 다음날 성과를 조회해요(읽기 전용).
     * 받는 것: 추천일(없으면 오늘의 직전 거래일), 실행 ID(있으면 날짜 대신 사용).
     * 돌려주는 것: 종목별 성과 응답 목록.
     */
    @Transactional(readOnly = true)
    public List<OvernightPerformanceResponse> list(LocalDate date, Long runId) {
        LocalDate targetDate = date == null ? calendar.previousTradingDay(calendar.today()) : date;
        return (runId == null ? performances.findByRecommendationDate(targetDate)
                : performances.findByRecommendationRunIdOrderByRecommendationRankAsc(runId))
                .stream().map(OvernightPerformanceResponse::from).toList();
    }

    /**
     * 하는 일: 추천 한 건의 다음날 성과를 새로 만들거나 갱신해요.
     * 받는 것: 잠금 조회한 추천, 목표·손절 수익률(%), 평가 시각(now).
     * 돌려주는 것: 저장된 성과 엔티티.
     */
    private OvernightPerformance evaluate(ClosingRecommendation recommendation, BigDecimal target, BigDecimal stop, Instant now) {
        // 준비: 이미 있는 성과면 옛 버전은 그대로 두고, 목표·손절이 처음과 다르면 거부하며, 이미 완료면 다시 계산하지 않아요.
        Optional<OvernightPerformance> existing = performances.findByRecommendationId(recommendation.getId());
        if (existing.isPresent()) {
            OvernightPerformance old = existing.get();
            if (!OvernightPerformance.OBSERVATION_VERSION.equals(old.getCalculationVersion())) return old;
            if (old.getTargetRate().compareTo(target) != 0 || old.getStopRate().compareTo(stop) != 0)
                throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                        "Observation thresholds are fixed. Create a new evaluation run to change them.");
            if (old.getStatus() == OvernightPerformanceStatus.COMPLETED) return old;
        }
        // 처음 만드는 성과면 다음 거래일과 그날 장 시작·마감 시각을 정해 둬요(ClosingTradingCalendar 기준).
        OvernightPerformance result = existing.orElseGet(() -> new OvernightPerformance(recommendation));
        if (existing.isEmpty()) {
            LocalDate next = calendar.nextTradingDay(recommendation.getRecommendationDate());
            result.initializeObservation(next, calendar.open(next), calendar.close(next), target, stop);
        }
        Instant open = result.getSessionOpen();
        Instant close = result.getSessionClose();
        // 처리: 다음 거래일의 확정 5분봉 중 5분 정렬, 이미 끝난 봉(봉 끝 <= now), 가격 정합인 것만 시간순으로 모아요.
        List<StockCandle> series = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        recommendation.getStock().getId(), "5M", open, close.plusSeconds(1))
                .stream().filter(StockCandle::isFinalCandle)
                .filter(c -> !c.getStartTime().isBefore(open) && !c.getStartTime().isAfter(close))
                .filter(c -> Duration.between(open, c.getStartTime()).getSeconds() % 300 == 0)
                .filter(c -> !end(c.getStartTime(), close).isAfter(now))
                .filter(this::validPrices).sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        // 09:00..15:15 regular bars plus the 15:25 closing-print bar; the 15:20 closing-call bar is optional.
        List<Instant> missing = NextSessionCoverage.missing(open, close, now, series);
        // 상태: 장 시작 전=PENDING, 마감+2분 전=IN_PROGRESS, 봉 없음=DATA_MISSING, 빠진 봉 있음=DATA_INCOMPLETE, 모두 있음=COMPLETED.
        OvernightPerformanceStatus state = now.isBefore(open) ? OvernightPerformanceStatus.PENDING
                : now.isBefore(NextSessionCoverage.settledAt(close)) ? OvernightPerformanceStatus.IN_PROGRESS
                : series.isEmpty() ? OvernightPerformanceStatus.DATA_MISSING
                : !missing.isEmpty() ? OvernightPerformanceStatus.DATA_INCOMPLETE
                : OvernightPerformanceStatus.COMPLETED;
        // 기준가는 추천의 매수 기준가예요. 시가는 첫 봉이 정확히 장 시작 봉일 때만 쓰고, 아니면 비워 둬요.
        BigDecimal base = recommendation.getBuyReferencePrice();
        BigDecimal first = series.isEmpty() || !series.getFirst().getStartTime().equals(open) ? null : series.getFirst().getOpen();
        BigDecimal latest = series.isEmpty() ? null : series.getLast().getClose();
        BigDecimal high = series.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null);
        BigDecimal low = series.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null);
        BigDecimal maximum = pct(high, base), minimum = pct(low, base);
        // 빠진 봉 시각 목록을 JSON으로 남겨 나중에 어떤 봉이 없었는지 확인할 수 있게 해요.
        String gaps;
        try { gaps = mapper.writeValueAsString(missing.stream().map(Instant::toString).toList()); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Failed to preserve coverage", error); }
        result.observe(now, series.isEmpty() ? null : end(series.getLast().getStartTime(), close), gaps, state,
                first, high, low, latest, pct(first, base), pct(latest, base), maximum, minimum,
                maximum != null && maximum.compareTo(target) >= 0, minimum != null && minimum.compareTo(stop) <= 0);
        // 결과: 완료일 때만 공식 가상 체결 결과를 기록하고 저장해요.
        if (state == OvernightPerformanceStatus.COMPLETED)
            recordOfficialExecution(recommendation, result, series, target, stop);
        return performances.save(result);
    }

    /**
     * 하는 일: 공식 FORWARD 실행의 추천이면 가상 진입가와 다음날 익절/손절 가상 체결 결과를 성과에 기록해요.
     * 받는 것: 추천, 성과 엔티티, 다음날 5분봉(완전한 묶음), 목표·손절 수익률(%).
     * 돌려주는 것: 없음(성과 엔티티에 직접 기록).
     */
    private void recordOfficialExecution(ClosingRecommendation recommendation, OvernightPerformance result,
            List<StockCandle> exitSeries, BigDecimal target, BigDecimal stop) {
        ClosingRecommendationRun run = recommendation.getRun();
        // FORWARD가 아니거나 완료되지 않은 실행이면 진입 없이 비용 가정만 남겨요.
        if (run == null || !"FORWARD".equals(run.getExecutionMode()) || run.getCompletedAt() == null) {
            result.recordExecution(null, null, null, execution.costAssumption());
            return;
        }
        // v9/v9_1(15:15) 전략은 평가 완료 뒤 실제로 관측한 체결로 가상 진입한 기록(ClosingPaperEntryService)을 써요. 진입이 없으면 체결 결과도 비워요.
        if (ClosingPaperEntryService.usesObservedEntry(run)) {
            var entry = paperEntries.find(run.getId(), recommendation.getStock().getStockCode());
            result.recordExecution(entry.at(), entry.price(), entry.price() == null ? null
                    : execution.targetOrStop(entry.price(), exitSeries, target, stop), execution.costAssumption());
            return;
        }
        // 그 밖의 옛 전략: 실행 완료 시각을 5분 단위로 올린 시각부터 추천일 마감 전까지의 첫 확정 5분봉 시가를 진입가로 써요.
        Instant entryStart = ceilFiveMinutes(run.getCompletedAt());
        Instant recommendationClose = calendar.close(recommendation.getRecommendationDate());
        StockCandle entry = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        recommendation.getStock().getId(), "5M", entryStart, recommendationClose)
                .stream().filter(StockCandle::isFinalCandle).filter(this::validPrices).findFirst().orElse(null);
        BigDecimal entryPrice = entry == null ? null : entry.getOpen();
        result.recordExecution(entry == null ? null : entry.getStartTime(), entryPrice,
                entryPrice == null ? null : execution.targetOrStop(entryPrice, exitSeries, target, stop),
                execution.costAssumption());
    }

    /**
     * 하는 일: 시각을 다음 5분 경계로 올려요(이미 경계면 그대로).
     * 받는 것: 시각. 돌려주는 것: 5분 단위로 올린 시각.
     */
    private Instant ceilFiveMinutes(Instant value) {
        long seconds = value.getEpochSecond();
        long rounded = Math.floorDiv(seconds + 299, 300) * 300;
        return Instant.ofEpochSecond(rounded);
    }

    /**
     * 하는 일: 5분봉의 끝 시각을 구하되 장 마감을 넘지 않게 잘라요.
     * 받는 것: 봉 시작 시각, 장 마감 시각. 돌려주는 것: min(시작+5분, 마감).
     */
    private Instant end(Instant start, Instant close) {
        Instant end = start.plusSeconds(300);
        return end.isAfter(close) ? close : end;
    }

    /**
     * 하는 일: 봉의 시가·고가·저가·종가가 모두 있고 서로 앞뒤가 맞는지(저가>0, 고가>=시가·종가, 저가<=시가·종가) 확인해요.
     * 받는 것: 5분봉. 돌려주는 것: 정상이면 true.
     */
    private boolean validPrices(StockCandle c) {
        return c.getOpen() != null && c.getHigh() != null && c.getLow() != null && c.getClose() != null
                && c.getLow().signum() > 0 && c.getHigh().compareTo(c.getOpen().max(c.getClose())) >= 0
                && c.getLow().compareTo(c.getOpen().min(c.getClose())) <= 0;
    }

    /**
     * 하는 일: 기준가 대비 가격 변화율(%)을 구해요.
     * 받는 것: 가격, 기준가. 돌려주는 것: 소수 6자리 변화율, 값이 없거나 기준가가 0 이하이면 null.
     */
    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() <= 0) return null;
        return price.subtract(base).divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
    }
}
