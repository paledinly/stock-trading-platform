/*
 * KIS에서 받은 날짜별 공식 개장 여부를 trading_calendar_day 테이블과 메모리에 보관하는 저장소예요.
 * 자주 불리는 조회는 메모리만 보고, 테이블은 처음 필요할 때 한 번만 읽어 와요. 받은 적 없는 날짜는 "모름"으로 두어 정적 휴장일(시장이 쉬는 날) 정책이 적용돼요.
 * ClosingTradingCalendar(거래일 판정), OfficialTradingCalendarRefresher(갱신), 관리용 API(ClosingRecommendationController)가 사용해요.
 */
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
 * 공식 개장 여부를 기억해 두는 메모장 클래스예요.
 * 학교에서 받은 휴교 안내문을 서랍(DB)에 넣어 두고, 자주 볼 내용은 책상 위 메모(메모리)에 옮겨 적어 빠르게 확인하는 것과 같아요. 안내문이 없는 날은 원래 학사력(정적 정책)을 따라요.
 *
 * Persisted provider market-open flags. Hot path reads memory only; the table is loaded once lazily.
 * Dates the provider never returned stay unknown so the static calendar policy applies.
 */
@Component
public class OfficialTradingDays {
    private static final Logger log = LoggerFactory.getLogger(OfficialTradingDays.class);
    /**
     * 상태 요약이에요: 알고 있는 날짜 수, 가장 이른·늦은 날짜, 마지막으로 받은 시각, 출처 이름.
     */
    public record Status(int knownDays, LocalDate firstDate, LocalDate lastDate, Instant lastFetchedAt, String source) {}
    private final JdbcTemplate jdbc;
    // 날짜별 개장 여부(true=개장, false=휴장) 메모리 사본, 테이블을 읽었는지 여부, 마지막으로 받은 시각이에요.
    private final Map<LocalDate, Boolean> days = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile Instant lastFetchedAt;

    /**
     * 하는 일: DB 접근 도구를 받아 저장소를 만들어요.
     * 받는 것: JdbcTemplate. 돌려주는 것: 없음(생성자).
     */
    public OfficialTradingDays(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
     * 하는 일: 테이블을 처음 한 번만 메모리로 읽어 와요(두 번 확인하는 잠금 방식으로 동시에 여러 번 읽지 않게 해요).
     * 받는 것: 없음. 돌려주는 것: 없음.
     */
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
                // ⚠️ 확인 필요: 읽기에 실패해도 아래에서 loaded=true가 되어, 재시작 전까지 DB에 있던 기록을 다시 읽지 않아요(그동안은 새로 받은 값과 정적 정책만 쓰여요).
                log.warn("Official trading days unavailable; static calendar policy applies: {}", error.getMessage());
            }
            loaded = true;
        }
    }
}
