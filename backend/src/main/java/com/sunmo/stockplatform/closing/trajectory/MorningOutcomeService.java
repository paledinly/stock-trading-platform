package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.closing.application.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryFeatures.*;

@Service
public class MorningOutcomeService {
    private final TrajectoryStore store;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final TrajectoryProperties policy;
    public MorningOutcomeService(TrajectoryStore store, StockRepository stocks, StockCandleRepository candles,
            ClosingTradingCalendar calendar, TrajectoryProperties policy) {
        this.store = store; this.stocks = stocks; this.candles = candles; this.calendar = calendar; this.policy = policy;
    }
    @Scheduled(fixedDelayString = "${closing.trajectory.outcome-interval:10m}", scheduler = "trajectoryContextScheduler")
    public void scheduled() {
        if (policy.enabled() && calendar.now().atZone(ClosingTradingCalendar.ZONE).toLocalTime().isAfter(LocalTime.of(9, 0)))
            track(calendar.previousTradingDay(calendar.today()));
    }
    public synchronized int track(LocalDate date) {
        List<Snapshot> snapshots = store.snapshots(date.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant(),
                date.plusDays(1).atStartOfDay(ClosingTradingCalendar.ZONE).toInstant());
        for (Snapshot snapshot : snapshots) {
            var stock = stocks.findByStockCode(snapshot.symbol()).orElse(null);
            if (stock == null) continue;
            LocalDate next = calendar.nextTradingDay(date);
            Instant open = calendar.open(next), end = next.atTime(snapshot.policy().morningEnd()).atZone(ClosingTradingCalendar.ZONE).toInstant();
            var series = candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                    stock.getId(), "5M", calendar.open(date), calendar.close(next));
            var valid = series.stream().filter(c -> valid(c, calendar.now())).sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
            BigDecimal close = valid.stream().filter(c -> c.getStartTime().equals(calendar.close(date).minusSeconds(300)))
                    .map(StockCandle::getClose).findFirst().orElse(null);
            // First whole candle after actual evaluation completion; 15:20 and later cannot be an entry.
            Instant earliest = Instant.ofEpochSecond(Math.floorDiv(snapshot.evaluatedAt().getEpochSecond(), 300) * 300 + 300);
            StockCandle entry = valid.stream().filter(c -> !c.getStartTime().isBefore(earliest)
                    && c.getStartTime().isBefore(date.atTime(15, 20).atZone(ClosingTradingCalendar.ZONE).toInstant()))
                    .findFirst().orElse(null);
            var morning = valid.stream().filter(c -> !c.getStartTime().isBefore(open) && c.getStartTime().isBefore(calendar.close(next))).toList();
            var outcome = new LinkedHashMap<>(calculate(morning, open, end, close,
                    entry == null ? null : entry.getOpen(), snapshot.policy().targetPercent(), snapshot.policy().stopPercent(), calendar.now()));
            outcome.put("entryAt", entry == null ? null : entry.getStartTime());
            outcome.put("recommendationDate", date); outcome.put("evaluationTime", snapshot.timestamp());
            outcome.put("evaluatedAt", snapshot.evaluatedAt()); outcome.put("entryModel", "NEXT_OBSERVED_FINAL_5M_OPEN_BEFORE_1520");
            outcome.put("mode", "SHADOW_GROSS_LABELS_NOT_EXECUTION_PNL"); store.outcome(snapshot, outcome);
        }
        return snapshots.size();
    }
    static boolean valid(StockCandle c, Instant now) {
        return c.isFinalCandle() && c.getTimeframe().equals("5M") && !c.getStartTime().plusSeconds(300).isAfter(now)
                && c.getCreatedAt() != null && c.getUpdatedAt() != null && !c.getCreatedAt().isAfter(now) && !c.getUpdatedAt().isAfter(now)
                && c.getLow().signum() > 0 && c.getHigh().compareTo(c.getOpen().max(c.getClose())) >= 0
                && c.getLow().compareTo(c.getOpen().min(c.getClose())) <= 0;
    }
    public static Map<String, Object> calculate(List<StockCandle> source, Instant open, Instant end,
            BigDecimal close, BigDecimal entry, BigDecimal target, BigDecimal stop, Instant now) {
        var rows = source.stream().filter(c -> valid(c, now) && !c.getStartTime().isBefore(open) && c.getStartTime().isBefore(end))
                .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        boolean complete = complete(rows, open, end) && !now.isBefore(end);
        BigDecimal first = rows.isEmpty() || !rows.getFirst().getStartTime().equals(open) ? null : rows.getFirst().getOpen();
        BigDecimal high = complete ? rows.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null) : null;
        BigDecimal low = complete ? rows.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null) : null;
        Map<String, Object> result = new LinkedHashMap<>(fields("status", complete && close != null ? "COMPLETED" : now.isBefore(end) ? "PENDING" : "DATA_INCOMPLETE",
                "closePrice", close, "entryPrice", entry, "nextOpenPrice", first, "nextOpenReturn", pct(first, close),
                "entryToNextOpenReturn", pct(first, entry), "nextMorningMaxReturn", pct(high, close),
                "nextMorningMaxDrawdown", pct(low, close), "returnBasis", "PRIOR_SESSION_CLOSE", "returnUnit", "PERCENT",
                "morningEnd", end, "targetPercent", target, "stopPercent", stop,
                "targetHit", high == null || close == null ? null : pct(high, close).compareTo(target) >= 0,
                "stopHitBeforeTarget", complete ? stopBeforeTarget(rows, close, target, stop) : null,
                "pathResolution", "FIVE_MINUTE_OHLC_NULL_IF_INTRABAR_ORDER_AMBIGUOUS"));
        for (int n : List.of(1, 2, 3, 5)) result.put("hit" + n + "Pct", high == null || close == null ? null : pct(high, close).compareTo(BigDecimal.valueOf(n)) >= 0);
        for (int minutes : List.of(60, 90, 120)) {
            Instant checkpoint = open.plusSeconds(minutes * 60L);
            var part = source.stream().filter(c -> valid(c, now) && !c.getStartTime().isBefore(open) && c.getStartTime().isBefore(checkpoint))
                    .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
            String suffix = minutes == 60 ? "1000" : minutes == 90 ? "1030" : "1100";
            result.put("nextHighUntil" + suffix, complete(part, open, checkpoint) ? part.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null) : null);
            result.put("nextLowUntil" + suffix, complete(part, open, checkpoint) ? part.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null) : null);
        }
        return result;
    }
    static boolean complete(List<StockCandle> rows, Instant open, Instant end) {
        if (rows.size() != Duration.between(open, end).toMinutes() / 5) return false;
        for (int i = 0; i < rows.size(); i++) if (!rows.get(i).getStartTime().equals(open.plusSeconds(i * 300L))) return false;
        return true;
    }
    public static Boolean stopBeforeTarget(List<StockCandle> rows, BigDecimal base, BigDecimal target, BigDecimal stop) {
        if (base == null || base.signum() <= 0) return null;
        BigDecimal tp = base.multiply(BigDecimal.ONE.add(target.movePointLeft(2))), sl = base.multiply(BigDecimal.ONE.add(stop.movePointLeft(2)));
        for (StockCandle r : rows) {
            if (r.getOpen().compareTo(sl) <= 0) return true;
            if (r.getOpen().compareTo(tp) >= 0) return false;
            boolean hit = r.getHigh().compareTo(tp) >= 0, stopped = r.getLow().compareTo(sl) <= 0;
            if (hit && stopped) return null; // Intrabar order cannot be recovered from OHLC.
            if (stopped) return true;
            if (hit) return false;
        }
        return false;
    }
}
