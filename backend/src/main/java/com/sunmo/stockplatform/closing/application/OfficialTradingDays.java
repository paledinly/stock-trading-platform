/*
 * KIS에서 받은 날짜별 공식 개장 여부를 trading_calendar_day 테이블과 메모리에 보관하는 저장소예요.
 * 자주 불리는 조회는 메모리만 보고, 테이블은 처음 필요할 때 한 번만 읽어 와요. 받은 적 없는 날짜는 "모름"으로 두어 정적 휴장일(시장이 쉬는 날) 정책이 적용돼요.
 * ClosingTradingCalendar(거래일 판정), OfficialTradingCalendarRefresher(갱신), 관리용 API(ClosingRecommendationController)가 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 공식 개장 여부를 기억해 두는 메모장 클래스예요.
 * 학교에서 받은 휴교 안내문을 서랍(DB)에 넣어 두고, 자주 볼 내용은 책상 위 메모(메모리)에 옮겨 적어 빠르게 확인하는 것과 같아요. 안내문이 없는 날은 원래 학사력(정적 정책)을 따라요.
 *
 * Persisted provider market-open flags. Hot path reads memory only; the table is loaded once lazily.
 * Dates the provider never returned stay unknown so the static calendar policy applies.
 */
@Component
public class OfficialTradingDays {
    private static final Logger log = LoggerFactory.getLogger(OfficialTradingDays.class);
    // 테이블 읽기에 실패했을 때 다시 읽기를 시도하기 전까지 기다리는 시간이에요. 조회가 몰릴 때 실패한 DB를 매번 두드리지 않게 해요.
    static final Duration LOAD_RETRY_BACKOFF = Duration.ofSeconds(30);
    /**
     * 상태 요약이에요: 알고 있는 날짜 수, 가장 이른·늦은 날짜, 마지막으로 받은 시각, 출처 이름.
     */
    public record Status(int knownDays, LocalDate firstDate, LocalDate lastDate, Instant lastFetchedAt, String source) {}
    private final JdbcTemplate jdbc;
    // 날짜별 개장 여부(true=개장, false=휴장) 메모리 사본, 테이블을 읽었는지 여부, 마지막으로 받은 시각이에요.
    private final Map<LocalDate, Boolean> days = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile Instant lastFetchedAt;
    // 재시도 대기 계산용 단조 시계(nanoTime)와, 마지막 읽기 실패 뒤 다시 시도해도 되는 시점이에요(실패한 적 없으면 null).
    private final LongSupplier nanoTime;
    private volatile Long retryAfterNanos;

    /**
     * 하는 일: DB 접근 도구를 받아 저장소를 만들어요.
     * 받는 것: JdbcTemplate. 돌려주는 것: 없음(생성자).
     */
    @Autowired
    public OfficialTradingDays(JdbcTemplate jdbc) { this(jdbc, System::nanoTime); }

    /**
     * 하는 일: 재시도 대기 시간을 계산할 시계를 바꿔 끼울 수 있는 생성자예요(테스트용).
     * 받는 것: JdbcTemplate, 나노초 단조 시계. 돌려주는 것: 없음(생성자).
     */
    OfficialTradingDays(JdbcTemplate jdbc, LongSupplier nanoTime) {
        this.jdbc = jdbc;
        this.nanoTime = nanoTime;
    }

    /**
     * 하는 일: 그 날짜에 시장이 열리는지 공급자 기록을 알려 줘요.
     * 받는 것: 날짜.
     * 돌려주는 것: 개장 true/휴장 false, 받은 적 없는 날짜면 빈 Optional(정적 정책을 따르라는 뜻).
     */
    public Optional<Boolean> marketOpen(LocalDate date) {
        load();
        return Optional.ofNullable(days.get(date));
    }

    /**
     * 하는 일: 새로 받은 개장 여부를 DB에 고치거나(update) 없으면 새로 넣고(insert), 메모리에도 반영해요.
     * 받는 것: 날짜별 개장 여부, 출처 이름, 받은 시각.
     * 돌려주는 것: 없음. 받은 값이 하나라도 있으면 마지막으로 받은 시각을 갱신해요.
     */
    public synchronized void record(Map<LocalDate, Boolean> fetched, String source, Instant fetchedAt) {
        load();
        // 처리: 날짜마다 update를 먼저 해 보고, 바뀐 행이 없으면 insert해요(트랜잭션=여러 저장을 한 묶음으로 처리하는 단위 없이 한 줄씩 저장해요).
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

    /**
     * 하는 일: 알고 있는 날짜 범위와 마지막 수신 시각을 요약해요.
     * 받는 것: 없음. 돌려주는 것: Status(출처는 항상 KIS_CHK_HOLIDAY로 표시).
     */
    public Status status() {
        load();
        List<LocalDate> known = days.keySet().stream().sorted().toList();
        return new Status(known.size(), known.isEmpty() ? null : known.getFirst(),
                known.isEmpty() ? null : known.getLast(), lastFetchedAt, "KIS_CHK_HOLIDAY");
    }

    /**
     * 하는 일: 테이블을 성공할 때까지 한 번만 메모리로 읽어 와요(두 번 확인하는 잠금 방식으로 동시에 여러 번 읽지 않게 해요).
     * 읽기에 실패하면 LOAD_RETRY_BACKOFF 동안은 다시 시도하지 않고, 그 뒤 다음 접근 때 다시 읽어요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
    private void load() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            Long retryAfter = retryAfterNanos;
            if (retryAfter != null && nanoTime.getAsLong() - retryAfter < 0) return;
            try {
                jdbc.query("select trade_date, market_open, fetched_at from trading_calendar_day", rs -> {
                    days.put(rs.getObject(1, LocalDate.class), rs.getBoolean(2));
                    Instant fetched = rs.getTimestamp(3).toInstant();
                    if (lastFetchedAt == null || fetched.isAfter(lastFetchedAt)) lastFetchedAt = fetched;
                });
                loaded = true;
                retryAfterNanos = null;
            } catch (RuntimeException error) {
                // 읽기에 실패하면 loaded를 true로 두지 않아요. 잠깐(LOAD_RETRY_BACKOFF) 기다린 뒤 다음 접근 때 다시 읽고,
                // 그 사이에는 이미 메모리에 있는 값(새로 받은 값 포함)과 정적 정책을 써요.
                retryAfterNanos = nanoTime.getAsLong() + LOAD_RETRY_BACKOFF.toNanos();
                log.warn("Official trading days unavailable; static calendar policy applies until retry: {}", error.getMessage());
            }
        }
    }
}
