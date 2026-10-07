package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Observed paper prices only: restart cannot manufacture a fill during the outage. */
@Service
public class ClosingPaperEntryService {
    public record Entry(Instant at, BigDecimal price, String status) {}
    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties policy;
    private final Map<String, ObservedMarketTick> latest = new ConcurrentHashMap<>();
    public ClosingPaperEntryService(JdbcTemplate jdbc, ClosingTradingCalendar calendar, ClosingRecommendationProperties policy) {
        this.jdbc = jdbc; this.calendar = calendar; this.policy = policy;
    }
    public static boolean usesObservedEntry(ClosingRecommendationRun run) {
        return "closing-recommend-v9-1515".equals(run.getStrategyVersion());
    }
    public void prepare(ClosingRecommendationRun run, Collection<String> symbols) {
        Instant deadline = run.getRecommendationDate().atTime(policy.entryDeadline()).atZone(ClosingTradingCalendar.ZONE).toInstant();
        String status = run.getCompletedAt().isBefore(deadline) ? "PENDING" : "EVALUATION_DEADLINE_MISSED";
        for (String symbol : new TreeSet<>(symbols)) jdbc.update(
                "insert into closing_paper_entry(run_id,stock_code,completed_at,deadline,status) values (?,?,?,?,?)",
                run.getId(), symbol, Timestamp.from(run.getCompletedAt()), Timestamp.from(deadline), status);
    }
    @TransactionalEventListener(fallbackExecution = true)
    public void receive(ObservedMarketTick event) {
        var local = event.receivedAt().atZone(ClosingTradingCalendar.ZONE).toLocalTime();
        if (local.isBefore(policy.featureFreezeAt()) || !local.isBefore(policy.entryDeadline())) return;
        latest.compute(event.tick().stockCode(), (code, old) -> old == null
                || event.receivedAt().isAfter(old.receivedAt()) ? event : old);
    }
    static boolean executable(ObservedMarketTick event, Instant completed, Instant now, Instant deadline) {
        if (event == null || event.dataLoss() || !now.isBefore(deadline)) return false;
        var tick = event.tick();
        return tick.price() != null && tick.price().signum() > 0 && !tick.tradingHalted()
                && tick.occurredAt().isAfter(completed) && !tick.occurredAt().isAfter(event.receivedAt())
                && !event.receivedAt().isAfter(now) && Duration.between(tick.occurredAt(), now).compareTo(Duration.ofSeconds(10)) <= 0;
    }
    @Scheduled(fixedDelayString = "${closing.automation.entry-poll-interval:5s}")
    public void capture() {
        Instant now = calendar.now(); var local = now.atZone(ClosingTradingCalendar.ZONE);
        // No all-day polling/egress. On the next active window, old pending entries are expired, never backfilled.
        if (!calendar.isTradingDay(local.toLocalDate()) || local.toLocalTime().isBefore(policy.featureFreezeAt())
                || local.toLocalTime().isAfter(policy.entryDeadline().plusMinutes(1))) return;
        jdbc.update("update closing_paper_entry set status='NO_FRESH_TICK_BEFORE_DEADLINE' where status='PENDING' and deadline<=?", Timestamp.from(now));
        jdbc.query("select run_id,stock_code,completed_at,deadline from closing_paper_entry where status='PENDING' and deadline>?",
                rs -> {
                    String symbol = rs.getString("stock_code"); var event = latest.get(symbol);
                    Instant deadline = rs.getTimestamp("deadline").toInstant();
                    Instant executed = calendar.now();
                    if (executable(event, rs.getTimestamp("completed_at").toInstant(), executed, deadline))
                        jdbc.update("update closing_paper_entry set status='ENTERED',source_at=?,received_at=?,entered_at=?,price=? where run_id=? and stock_code=? and status='PENDING'",
                                Timestamp.from(event.tick().occurredAt()), Timestamp.from(event.receivedAt()), Timestamp.from(executed),
                                event.tick().price(), rs.getLong("run_id"), symbol);
                }, Timestamp.from(now));
        if (!local.toLocalTime().isBefore(policy.entryDeadline())) latest.clear();
    }
    public Entry find(long run, String symbol) {
        return jdbc.query("select entered_at,price,status from closing_paper_entry where run_id=? and stock_code=?",
                (rs, index) -> new Entry(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), rs.getBigDecimal(2), rs.getString(3)),
                run, symbol).stream().findFirst().orElse(new Entry(null, null, "ENTRY_NOT_CAPTURED"));
    }
}
