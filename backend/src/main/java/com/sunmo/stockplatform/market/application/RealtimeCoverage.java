/*
 * 종목별로 실시간 체결을 언제부터 끊김 없이 받고 있는지 기억해, 장중 재시작·재연결로 생긴 빈 구간을 찾아요.
 * 빈 구간 안에서 실시간으로 만든 봉은 일부 체결만 담은 봉이라 확정봉으로 저장하지 않게 알려 주고(MarketDataService),
 * 빈 구간 목록을 RealtimeGapRepairService에 넘겨 KIS 분봉으로 채우게 해요.
 * 처음 구독한 종목이나 장중에 새로 구독한 종목은 대상이 아니에요(평소 평가 입력을 바꾸지 않기 위해서예요).
 */
package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실시간 수신 연속성 장부예요. 시각은 모두 호스트 수신 시각(구독 응답·끊김을 알아챈 시각)이에요.
 * 빈 구간은 두 경우만 만들어요.
 * DISCONNECT: 이 프로세스에서 받던 종목이 연결 끊김으로 멈췄다가 다시 구독된 경우. 끊긴 시각이 든 칸부터 빈 구간이에요.
 * RESTART: 프로세스가 장중에 새로 떠서 처음 구독된 종목 중, 프로세스 시작 직전(15분 안)까지 실시간 확정봉이 저장돼 있던 종목.
 * 그 마지막 확정봉 다음 칸부터 빈 구간이에요.
 */
@Component
public class RealtimeCoverage {
    private static final Logger log = LoggerFactory.getLogger(RealtimeCoverage.class);
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration BUCKET = Duration.ofMinutes(5);
    /** 재시작 전 프로세스가 이 시간 안에 실시간 봉을 남겼어야 재시작으로 끊긴 종목으로 봐요. */
    static final Duration RESTART_LOOKBACK = Duration.ofMinutes(15);

    /** 빈 구간의 원인이에요. */
    public enum Cause { DISCONNECT, RESTART }

    /**
     * 종목 하나의 빈 구간이에요. lostFrom: 빈 구간 첫 칸(RESTART는 DB를 본 뒤 정해요),
     * resumedAt: 다시 구독된 시각(아직이면 null). rejected: 재시작 대상이 아니라고 판단됨.
     */
    public static final class Gap {
        final String code;
        final Cause cause;
        Instant lostFrom;
        Instant resumedAt;
        boolean rejected;
        boolean done;
        int attempts;

        Gap(String code, Cause cause, Instant lostFrom, Instant resumedAt) {
            this.code = code;
            this.cause = cause;
            this.lostFrom = lostFrom;
            this.resumedAt = resumedAt;
        }

        public String code() { return code; }
        public Cause cause() { return cause; }
        public Instant lostFrom() { return lostFrom; }
        public Instant resumedAt() { return resumedAt; }
        public int attempts() { return attempts; }

        /** 다시 구독된 시각이 든 칸(장 마감 뒤면 마지막 15:25 칸)이에요. 이 칸까지가 빈 구간이에요. */
        public Instant lastBucket() {
            return clampedBucket(resumedAt);
        }
    }

    private final JdbcTemplate jdbc;
    private final Instant processStart;
    private final Set<String> covered = new HashSet<>();
    private final Set<String> everCovered = new HashSet<>();
    private final Map<String, Gap> gaps = new HashMap<>();
    private LocalDate day;

    /** 받는 것: 마지막 실시간 확정봉을 찾을 JdbcTemplate. 프로세스 시작 시각은 이 객체를 만든 시각이에요. */
    @org.springframework.beans.factory.annotation.Autowired
    public RealtimeCoverage(JdbcTemplate jdbc) {
        this(jdbc, Instant.now());
    }

    RealtimeCoverage(JdbcTemplate jdbc, Instant processStart) {
        this.jdbc = jdbc;
        this.processStart = processStart;
    }

    /** DB 없이 쓰는 장부예요(재시작 구간은 찾지 않아요). 테스트·옛 생성자용이에요. */
    public static RealtimeCoverage untracked() {
        return new RealtimeCoverage(null, Instant.now());
    }

    /** 하는 일: 종목 구독이 성공했다고 기록해요. 끊겼던 종목이면 빈 구간을 닫고, 장중 재시작 뒤 첫 구독이면 재시작 구간 후보를 만들어요. */
    public synchronized void subscribed(String code, Instant at) {
        rollDay(at);
        if (!covered.add(code))
            return;
        boolean first = everCovered.add(code);
        Gap gap = gaps.get(code);
        if (gap != null && !gap.done && gap.resumedAt == null) {
            gap.resumedAt = at;
            log.info("Realtime coverage resumed for {} ({}): lost from {}", code, gap.cause, gap.lostFrom);
            return;
        }
        if (first && jdbc != null && inSession(at) && gap == null)
            gaps.put(code, new Gap(code, Cause.RESTART, null, at));
    }

    /** 하는 일: 종목 구독 해지를 기록해요. 이미 생긴 빈 구간은 그대로 둬요. */
    public synchronized void unsubscribed(String code) {
        covered.remove(code);
    }

    /** 하는 일: 장중 연결 끊김을 기록해요. 받고 있던 모든 종목에 끊긴 시각이 든 칸부터 빈 구간을 열어요. */
    public synchronized void disconnected(Instant at) {
        rollDay(at);
        if (inSession(at)) {
            Instant from = clampedBucket(at);
            for (String code : covered) {
                Gap existing = gaps.get(code);
                if (existing == null || existing.done || existing.rejected)
                    gaps.put(code, new Gap(code, Cause.DISCONNECT, from, null));
                else if (existing.resumedAt != null) // 아직 채우기 전 구간이 있으면 끝을 다시 열어 둬요.
                    existing.resumedAt = null;
            }
        }
        covered.clear();
    }

    /** 하는 일: 장 운영시간 밖이라 연결을 쉬는 것(계획된 끊김)을 기록해요. 빈 구간으로 보지 않아요. */
    public synchronized void paused() {
        covered.clear();
    }

    /**
     * 하는 일: 이 칸의 실시간 봉이 빈 구간에 걸쳐 일부 체결만 담았는지 알려 줘요.
     * 받는 것: 종목코드, 칸 시작시각. 돌려주는 것: 빈 구간 첫 칸 이후이고 다시 구독되기 전에 시작된 칸이면 true.
     */
    public synchronized boolean partial(String code, Instant bucketStart) {
        Gap gap = resolved(code);
        if (gap == null || bucketStart.isBefore(gap.lostFrom))
            return false;
        return gap.resumedAt == null || gap.resumedAt.isAfter(bucketStart);
    }

    /** 하는 일: 다시 구독돼 채울 수 있는, 아직 끝나지 않은 빈 구간 목록을 돌려줘요. */
    public synchronized List<Gap> repairable() {
        List<Gap> result = new ArrayList<>();
        for (String code : new ArrayList<>(gaps.keySet())) {
            Gap gap = resolved(code);
            if (gap != null && !gap.done && gap.resumedAt != null)
                result.add(gap);
        }
        return result;
    }

    /** 하는 일: 채우기 시도 횟수를 하나 올려요. */
    public synchronized void attempted(Gap gap) {
        gap.attempts++;
    }

    /** 하는 일: 빈 구간 처리를 끝냈다고 기록해요(채움 완료 또는 시도 한도 도달). */
    public synchronized void finished(Gap gap) {
        gap.done = true;
    }

    /** RESTART 후보면 DB에서 마지막 실시간 확정봉을 찾아 빈 구간 시작을 정하거나 대상에서 빼요. */
    private Gap resolved(String code) {
        Gap gap = gaps.get(code);
        if (gap == null || gap.rejected)
            return null;
        if (gap.lostFrom != null)
            return gap;
        Instant last = lastRealtimeFinal(code, gap.lastBucket());
        if (last == null || last.isBefore(processStart.minus(RESTART_LOOKBACK))) {
            gap.rejected = true;
            return null;
        }
        gap.lostFrom = last.plus(BUCKET);
        if (gap.lostFrom.isAfter(gap.lastBucket())) {
            gap.rejected = true; // 재시작 전 마지막 봉 바로 다음 칸에 다시 구독돼 빈 칸이 없어요.
            return null;
        }
        log.info("Realtime coverage gap after restart for {}: {} ~ resumed {}", code, gap.lostFrom, gap.resumedAt);
        return gap;
    }

    /** 오늘 장 시작부터 before 전까지 저장된 이 종목의 마지막 실시간 확정 5분봉 시작시각이에요. */
    private Instant lastRealtimeFinal(String code, Instant before) {
        Instant open = open(before.atZone(SEOUL).toLocalDate());
        Timestamp last = jdbc.queryForObject("select max(c.start_time) from stock_candle c join stock s on s.id = c.stock_id "
                + "where s.stock_code = ? and c.timeframe = '5M' and c.source = 'REALTIME' and c.is_final = true "
                + "and c.start_time >= ? and c.start_time < ?", Timestamp.class, code, Timestamp.from(open),
                Timestamp.from(before));
        return last == null ? null : last.toInstant();
    }

    /** 날짜가 바뀌면 전날 장부를 비워요. */
    private void rollDay(Instant at) {
        LocalDate today = at.atZone(SEOUL).toLocalDate();
        if (!today.equals(day)) {
            day = today;
            gaps.clear();
            everCovered.clear();
            everCovered.addAll(covered);
        }
    }

    /** 정규장 체결을 받는 시간(09:00~15:31)인지 알려 줘요. */
    static boolean inSession(Instant at) {
        LocalTime time = at.atZone(SEOUL).toLocalTime();
        return !time.isBefore(LocalTime.of(9, 0))
                && time.isBefore(LocalTime.of(15, 30).plus(FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW));
    }

    /** 시각이 든 5분 칸이에요. 09:00 전이면 09:00 칸, 15:25 뒤면 15:25 칸으로 맞춰요. */
    static Instant clampedBucket(Instant at) {
        LocalDate date = at.atZone(SEOUL).toLocalDate();
        if (at.isBefore(open(date)))
            return open(date);
        Instant last = date.atTime(15, 25).atZone(SEOUL).toInstant();
        if (!at.isBefore(last))
            return last;
        return FiveMinuteCandleAggregator.bucketStart(at);
    }

    private static Instant open(LocalDate date) {
        return date.atTime(9, 0).atZone(SEOUL).toInstant();
    }
}
