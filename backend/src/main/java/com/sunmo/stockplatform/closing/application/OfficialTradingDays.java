package com.sunmo.stockplatform.closing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persisted provider market-open flags. Hot path reads memory only; the table is loaded once lazily.
 * Dates the provider never returned stay unknown so the static calendar policy applies.
 */
@Component
public class OfficialTradingDays {
    private static final Logger log = LoggerFactory.getLogger(OfficialTradingDays.class);
    public record Status(int knownDays, LocalDate firstDate, LocalDate lastDate, Instant lastFetchedAt, String source) {}
    private final JdbcTemplate jdbc;
    private final Map<LocalDate, Boolean> days = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile Instant lastFetchedAt;

    public OfficialTradingDays(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Boolean> marketOpen(LocalDate date) {
        load();
        return Optional.ofNullable(days.get(date));
    }

    public synchronized void record(Map<LocalDate, Boolean> fetched, String source, Instant fetchedAt) {
        load();
        for (var entry : fetched.entrySet()) {
            Timestamp at = Timestamp.from(fetchedAt);
            int updated = jdbc.update("update trading_calendar_day set market_open=?, source=?, fetched_at=? where trade_date=?",
                    entry.getValue(), source, at, entry.getKey());
            if (updated == 0)
                jdbc.update("insert into trading_calendar_day(trade_date, market_open, source, fetched_at) values (?,?,?,?)",
                        entry.getKey(), entry.getValue(), source, at);
            days.put(entry.getKey(), entry.getValue());
        }
        if (!fetched.isEmpty()) lastFetchedAt = fetchedAt;
    }

    public Status status() {
        load();
        List<LocalDate> known = days.keySet().stream().sorted().toList();
        return new Status(known.size(), known.isEmpty() ? null : known.getFirst(),
                known.isEmpty() ? null : known.getLast(), lastFetchedAt, "KIS_CHK_HOLIDAY");
    }

    private void load() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            try {
                jdbc.query("select trade_date, market_open, fetched_at from trading_calendar_day", rs -> {
                    days.put(rs.getObject(1, LocalDate.class), rs.getBoolean(2));
                    Instant fetched = rs.getTimestamp(3).toInstant();
                    if (lastFetchedAt == null || fetched.isAfter(lastFetchedAt)) lastFetchedAt = fetched;
                });
            } catch (RuntimeException error) {
                log.warn("Official trading days unavailable; static calendar policy applies: {}", error.getMessage());
            }
            loaded = true;
        }
    }
}
