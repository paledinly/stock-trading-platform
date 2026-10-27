/*
 * 장 마감 뒤, 전날 공식 추천 실행의 정밀 후보·추천 종목에 대해 오늘 빠진 5분봉을 KIS 분봉(1분 단위 가격 막대)으로 채우는 서비스예요.
 * 15:30 종가 동시호가 체결분은 15:25 봉에 합쳐지고, 실시간으로 이미 확정된 봉은 덮어쓰지 않아요.
 * ClosingRecommendationScheduler가 15:40(마감+10분) 관측 실행 직전에 부르며, closing.automation.enabled=true일 때만 만들어져요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.candle.application.CandleGapDetector;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 다음날 관측에 쓸 5분봉의 빈칸을 장 마감 뒤 한꺼번에 메우는 클래스예요.
 * 실시간 녹화를 놓친 장면만 방송 끝난 뒤 다시보기(분봉 조회)로 채워 넣는 편집자라고 생각하면 돼요. 이미 녹화된 장면은 건드리지 않아요.
 *
 * ADR-010: every precision candidate of the previous official run is a primary sample, but only up to ten of them
 * get a realtime slot on the next session. After the closing auction, the missing 5M bars of today's session are
 * filled from KIS minute candles (source BACKFILL) before the 15:40 observation run, without using realtime slots.
 * Realtime final bars are never overwritten. Invoked by ClosingRecommendationScheduler before observation tracking.
 */
@Component
@ConditionalOnProperty(prefix = "closing.automation", name = "enabled", havingValue = "true")
public class ObservationBackfillService {
    private static final Logger log = LoggerFactory.getLogger(ObservationBackfillService.class);
    /**
     * 종가 동시호가 체결(15:30:00~15:31:00)이 확정된 뒤에 분봉을 읽도록 마감 후 2분을 기다려요.
     * The closing auction print (15:30:00..15:31:00) must be final before minute candles are read.
     */
    static final Duration AFTER_CLOSE = Duration.ofMinutes(2);

    /**
     * 한 번의 보충 결과 요약이에요: 기준 실행 ID, 대상 종목 수, 채운 봉 수, 채운 뒤에도 빈칸이 남은 종목 수.
     */
    public record Result(Long runId, int stocks, int filledBars, int stillMissingStocks) { }

    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationRunRepository runs;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final CandleBackfillService backfill;
    private final JdbcTemplate jdbc;
    // 하루 5분봉 칸 중 비어 있는 칸(갭)을 찾는 도구예요.
    private final CandleGapDetector gaps = new CandleGapDetector();

    /**
     * 하는 일: 의존 객체를 받아 서비스를 만들어요.
     * 받는 것: 거래일 달력, 추천 실행 저장소, 종목 저장소, 봉 저장소, 분봉 보충 서비스, JdbcTemplate.
     * 돌려주는 것: 없음(생성자).
     */
    public ObservationBackfillService(ClosingTradingCalendar calendar, ClosingRecommendationRunRepository runs,
            StockRepository stocks, StockCandleRepository candles, CandleBackfillService backfill, JdbcTemplate jdbc) {
        this.calendar = calendar;
        this.runs = runs;
        this.stocks = stocks;
        this.candles = candles;
        this.backfill = backfill;
        this.jdbc = jdbc;
    }

    /**
     * 하는 일: fillToday를 실행하고, 건너뜀·성공·실패 모두를 로그로 남겨요.
     * 받는 것: 없음. 돌려주는 것: 결과 요약(건너뛰었거나 실패하면 null).
     *
     * Called by the closing scheduler right before the close + 10m observation run, so bars are filled before they are
     * judged. A separate cron registration left no trace on 2026-10-14, so this rides the path that is known to run.
     * Every outcome, including a skip, is logged.
     */
    public Result fillTodayLogged() {
        try {
            Result result = fillToday();
            log.info("Observation backfill: {}", result == null ? "skipped (not after close on a trading day, or no "
                    + "previous official run)" : result);
            return result;
        } catch (RuntimeException error) {
            log.warn("Observation backfill failed: {}", error.getMessage());
            return null;
        }
    }

    /**
     * 하는 일: 오늘이 거래일이고 마감+2분이 지났으면, 전 거래일 FORWARD(실전 관측용) 실행의 대상 종목마다 오늘 빠진 5분봉을 채워요.
     * 받는 것: 없음(시각은 ClosingTradingCalendar에서 가져와요).
     * 돌려주는 것: 결과 요약. 거래일이 아니거나 너무 이르거나 전 거래일 실행이 없으면 null.
     */
    Result fillToday() {
        // 준비: 거래일인지, 마감+2분이 지났는지 확인해요.
        LocalDate today = calendar.today();
        Instant now = calendar.now();
        if (!calendar.isTradingDay(today) || now.isBefore(calendar.close(today).plus(AFTER_CLOSE)))
            return null;
        // 전 거래일의 가장 최근 FORWARD 실행을 기준으로 삼아요.
        LocalDate previous = calendar.previousTradingDay(today);
        ClosingRecommendationRun run = runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(previous, "FORWARD")
                .orElse(null);
        if (run == null)
            return null;
        // 대상: 그 실행의 정밀 후보(PRECISION) 관측 종목 + 실제 추천 종목(중복 제거).
        List<Long> stockIds = jdbc.queryForList("""
                select stock_id from closing_candidate_observation where run_id = ? and candidate_source = 'PRECISION'
                union
                select stock_id from closing_recommendation where run_id = ?""", Long.class, run.getId(), run.getId());
        // 장 시작·마감은 달력 기준이에요(수능일 등 특별 일정은 MarketSessionHours 설정을 따라요).
        Instant open = calendar.open(today);
        Instant close = calendar.close(today);
        // 처리: 종목마다 빈칸을 찾고, 있으면 분봉으로 채운 뒤 다시 확인해 아직 남았는지 세요.
        int filled = 0;
        int stillMissing = 0;
        for (Long stockId : stockIds) {
            Stock stock = stocks.findById(stockId).orElse(null);
            if (stock == null)
                continue;
            List<Instant> missing = missing(stock, today, open, close);
            if (missing.isEmpty())
                continue;
            filled += backfill.backfillToday(stock, missing, now);
            if (!missing(stock, today, open, close).isEmpty())
                stillMissing++;
        }
        return new Result(run.getId(), stockIds.size(), filled, stillMissing);
    }

    /**
     * 하는 일: 한 종목의 오늘 5분봉 중 비어 있는 칸의 시작 시각을 찾아요.
     * 받는 것: 종목, 오늘 날짜, 장 시작·마감 시각.
     * 돌려주는 것: 빈칸 시작 시각 목록(15:20 동시호가 칸은 빼요). 거래량 0인 15:25 봉도 빈칸으로 봐요.
     */
    private List<Instant> missing(Stock stock, LocalDate today, Instant open, Instant close) {
        List<Instant> existing = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        stock.getId(), "5M", open, close)
                .stream().filter(StockCandle::isFinalCandle)
                // A flat zero-volume 15:25 bar has no closing print yet; refill it.
                .filter(candle -> !isFlatClosingBar(candle, today))
                .map(StockCandle::getStartTime).toList();
        // 15:20 is the closing call (no trades) and is never required; drop it from the gaps.
        // 15:20/15:25는 평소 시각이고, 마감이 늦춰진 날은 그만큼 옮겨요(CandleGapDetector와 같은 MarketSessionHours 기준).
        Instant closingCall = MarketSessionHours.at(today, LocalTime.of(15, 20));
        return gaps.gaps(today, close, existing).stream().filter(gap -> !gap.equals(closingCall)).toList();
    }

    /**
     * 하는 일: 이 봉이 "아직 종가 체결이 안 담긴 15:25 봉(거래량 0)"인지 확인해요.
     * 받는 것: 5분봉, 오늘 날짜. 돌려주는 것: 15:25 시작이고 거래량이 0 이하이면 true.
     */
    private static boolean isFlatClosingBar(StockCandle candle, LocalDate today) {
        Instant closingBar = MarketSessionHours.at(today, LocalTime.of(15, 25));
        return candle.getStartTime().equals(closingBar) && candle.getVolume() <= 0;
    }
}
