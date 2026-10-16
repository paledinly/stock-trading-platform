/*
 * 장중 재시작·재연결로 실시간 수신이 끊긴 구간의 5분봉을 KIS 분봉으로 채우고, 그 구간을 realtime_coverage_gap에 기록해요.
 * RealtimeCoverage가 찾은 빈 구간(다시 구독된 종목)만 대상이에요. 2분마다 돌며, 다시 구독된 칸이 끝난 뒤 시도해요.
 * 채운 봉은 출처 BACKFILL이고 실시간 확정봉은 덮지 않아요(CandleBackfillService 규칙). 실패해도 최대 3번까지만 시도해요.
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 끊긴 구간 메우기 담당이에요. 평가 입력에 쓰이는 봉도 같은 테이블이라, 대상은 재시작·재연결 구간으로만 좁혀요. */
@Service
public class RealtimeGapRepairService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeGapRepairService.class);
    static final int MAX_ATTEMPTS = 3;
    /** 다시 구독된 칸이 끝나고(15:25 칸은 종가 체결 대기 포함) 실시간 봉이 저장될 때까지 기다리는 시간이에요. */
    static final Duration SETTLE = Duration.ofMinutes(6).plusSeconds(30);
    private static final Duration BUCKET = Duration.ofMinutes(5);

    private final RealtimeCoverage coverage;
    private final CandleBackfillService backfill;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public RealtimeGapRepairService(RealtimeCoverage coverage, CandleBackfillService backfill, StockRepository stocks,
            StockCandleRepository candles, ClosingTradingCalendar calendar, JdbcTemplate jdbc) {
        this(coverage, backfill, stocks, candles, calendar, jdbc, true);
    }

    /** 받는 것: 위와 같고, 끊긴 구간 채우기를 켤지(MARKET_GAP_REPAIR_ENABLED, 기본 켜짐). 꺼도 미확정 저장과 장부는 그대로예요. */
    @org.springframework.beans.factory.annotation.Autowired
    public RealtimeGapRepairService(RealtimeCoverage coverage, CandleBackfillService backfill, StockRepository stocks,
            StockCandleRepository candles, ClosingTradingCalendar calendar, JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${market.realtime.gap-repair-enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.coverage = coverage;
        this.backfill = backfill;
        this.stocks = stocks;
        this.candles = candles;
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    /** 하는 일: 2분마다 채울 수 있는 빈 구간을 처리해요. */
    @Scheduled(fixedDelayString = "${market.realtime.gap-repair-interval:2m}")
    public void repairDue() {
        if (enabled)
            repairDue(calendar.now());
    }

    /**
     * 하는 일: 다시 구독된 칸이 끝난 빈 구간마다 비었거나 미확정인 5분봉을 KIS 분봉으로 채워요.
     * 다 채웠거나 시도 한도에 닿으면 기록을 남기고 끝내요. 받는 것: 현재 시각.
     */
    void repairDue(Instant now) {
        for (RealtimeCoverage.Gap gap : coverage.repairable()) {
            if (gap.resumedAt() == null || now.isBefore(gap.lastBucket().plus(SETTLE)))
                continue;
            var stock = stocks.findByStockCodeAndActiveTrue(gap.code()).orElse(null);
            if (stock == null) {
                coverage.finished(gap);
                continue;
            }
            List<Instant> targets = targets(gap);
            List<Instant> missing = missing(stock.getId(), targets);
            int before = missing.size();
            if (!missing.isEmpty()) {
                coverage.attempted(gap);
                backfill.backfillToday(stock, missing, now);
                missing = missing(stock.getId(), targets);
            }
            if (missing.isEmpty() || gap.attempts() >= MAX_ATTEMPTS) {
                coverage.finished(gap);
                record(gap, targets.size(), Math.max(0, targets.size() - missing.size()), missing.size(), now);
                log.info("Realtime gap repair {} {} {}~{}: target={}, missingBefore={}, stillMissing={}, attempts={}",
                        gap.code(), gap.cause(), gap.lostFrom(), gap.lastBucket(), targets.size(), before,
                        missing.size(), gap.attempts());
            }
        }
    }

    /** 빈 구간 첫 칸부터 다시 구독된 칸까지의 5분 칸이에요. 15:20 칸(종가 동시호가, 체결 없음)은 빼요. */
    static List<Instant> targets(RealtimeCoverage.Gap gap) {
        List<Instant> result = new ArrayList<>();
        for (Instant bucket = gap.lostFrom(); !bucket.isAfter(gap.lastBucket()); bucket = bucket.plus(BUCKET)) {
            if (!bucket.atZone(RealtimeCoverage.SEOUL).toLocalTime().equals(LocalTime.of(15, 20)))
                result.add(bucket);
        }
        return result;
    }

    /** 대상 칸 중 봉이 없거나 미확정인 칸이에요. */
    private List<Instant> missing(Long stockId, List<Instant> targets) {
        if (targets.isEmpty())
            return List.of();
        Set<Instant> finals = candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        stockId, "5M", targets.getFirst(), targets.getLast().plus(BUCKET))
                .stream().filter(StockCandle::isFinalCandle).map(StockCandle::getStartTime).collect(Collectors.toSet());
        return targets.stream().filter(bucket -> !finals.contains(bucket)).toList();
    }

    /** 빈 구간 하나를 realtime_coverage_gap에 남겨요. 같은 종목·재구독 시각이면 갱신해요. */
    private void record(RealtimeCoverage.Gap gap, int target, int repaired, int stillMissing, Instant now) {
        Object[] values = {gap.cause().name(), Timestamp.from(gap.lostFrom()), target, repaired, stillMissing,
                gap.attempts(), Timestamp.from(now), gap.code(), Timestamp.from(gap.resumedAt())};
        try {
            int updated = jdbc.update("update realtime_coverage_gap set cause = ?, lost_from = ?, target_bars = ?, "
                    + "repaired_bars = ?, still_missing = ?, attempts = ?, recorded_at = ? where stock_code = ? and resumed_at = ?",
                    values);
            if (updated == 0)
                jdbc.update("insert into realtime_coverage_gap(session_date, stock_code, cause, lost_from, resumed_at, "
                        + "target_bars, repaired_bars, still_missing, attempts, recorded_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Date.valueOf(gap.resumedAt().atZone(RealtimeCoverage.SEOUL).toLocalDate()), gap.code(),
                        gap.cause().name(), Timestamp.from(gap.lostFrom()), Timestamp.from(gap.resumedAt()), target, repaired,
                        stillMissing, gap.attempts(), Timestamp.from(now));
        } catch (RuntimeException error) {
            log.warn("Realtime gap record failed for {}: {}", gap.code(), error.getMessage());
        }
    }
}
