/*
 * 날짜별 정규장 시간을 알려 주는 곳이에요. 평소는 09:00~15:30이고, 수능일처럼 거래소가 시간을 바꾼 날만 설정으로 따로 적어요.
 * 마감 기준 작업(15:15 평가, 15:20 진입, 14:50 고정, 15:25 마지막 봉 등)은 모두 "평소 시각"으로 적혀 있어서,
 * 그날 마감이 늦춰진 만큼(shift) 벽시계 시각을 평소 시각으로 바꿔 비교하고, 평소 시각을 그날 실제 시각으로 바꿔 저장해요.
 */
package com.sunmo.stockplatform.closing.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;

/**
 * 하는 일: 날짜별 개장·마감 시각과, 평소 시각 ↔ 그날 실제 시각 변환을 제공해요.
 * 시계 바늘을 한 시간 늦게 맞춘 날처럼, 평소 일정표(15:15 평가)를 그날 시계(16:15)로 옮겨 읽게 해 줘요.
 * 설정 형식: market.special-sessions=2026-11-19@10:00-16:30,2027-01-04@10:00-15:30 (쉼표로 여러 날).
 * 마감 이동은 0분 또는 60분만 허용해요. 고정 시각 작업(cron)이 평소 시각과 한 시간 뒤 두 번만 깨어나 확인하기 때문이에요.
 * 설정은 시작할 때 한 번 읽어 정적으로 보관해요. 5분봉 집계처럼 스프링 빈이 아닌 계산에서도 같은 값을 쓰기 위해서예요.
 */
@Component
public class MarketSessionHours {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final LocalTime REGULAR_OPEN = LocalTime.of(9, 0);
    public static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 30);
    /** 고정 시각 작업이 늦게 깨어나도 같은 작업으로 인정하는 여유예요(한 시간 차이를 구분할 만큼만 짧게). */
    static final Duration FIRE_TOLERANCE = Duration.ofMinutes(10);

    /** 특별 개장일의 개장·마감 시각이에요. */
    public record Session(LocalTime open, LocalTime close) { }

    private static volatile Map<LocalDate, Session> special = Map.of();

    /** 스프링이 설정값으로 만들 때 쓰는 생성자예요. 받은 특별 개장일을 검증해 정적으로 보관해요. */
    public MarketSessionHours(@Value("${market.special-sessions:}") String config) {
        configure(parse(config));
    }

    /**
     * 하는 일: 설정 문자열을 날짜별 Session으로 읽어요. 형식이 틀리거나 허용하지 않는 시간이면 시작을 막아요.
     * 받는 것: "2026-11-19@10:00-16:30" 들을 쉼표로 이은 문자열. 돌려주는 것: 날짜별 Session.
     */
    public static Map<LocalDate, Session> parse(String config) {
        Map<LocalDate, Session> result = new TreeMap<>();
        if (config == null || config.isBlank()) return result;
        for (String item : config.split(",")) {
            String value = item.trim();
            if (value.isEmpty()) continue;
            String[] dateAndHours = value.split("@");
            String[] hours = dateAndHours.length == 2 ? dateAndHours[1].split("-") : new String[0];
            if (hours.length != 2)
                throw new IllegalArgumentException("market.special-sessions item must look like 2026-11-19@10:00-16:30: " + value);
            result.put(LocalDate.parse(dateAndHours[0].trim()),
                    new Session(LocalTime.parse(hours[0].trim()), LocalTime.parse(hours[1].trim())));
        }
        return result;
    }

    /**
     * 하는 일: 특별 개장일 목록을 바꿔요. 테스트에서 직접 부르고, 끝나면 빈 목록으로 되돌려야 해요.
     * 받는 것: 날짜별 Session. 마감 이동이 0분·60분이 아니거나 개장이 마감보다 늦으면 IllegalArgumentException.
     */
    public static void configure(Map<LocalDate, Session> sessions) {
        for (var entry : sessions.entrySet()) {
            Session session = entry.getValue();
            Duration shift = Duration.between(REGULAR_CLOSE, session.close());
            if (!shift.isZero() && !shift.equals(Duration.ofHours(1)))
                throw new IllegalArgumentException("Only a 0 or 60 minute close shift is supported: " + entry.getKey());
            if (session.open().isBefore(REGULAR_OPEN) || !session.open().isBefore(session.close()))
                throw new IllegalArgumentException("Invalid special session hours: " + entry.getKey());
        }
        special = Map.copyOf(sessions);
    }

    /** 설정된 특별 개장일 전체예요(화면·진단용). */
    public static Map<LocalDate, Session> specialSessions() { return special; }

    /** 그날 개장 시각(벽시계)이에요. */
    public static LocalTime open(LocalDate date) {
        Session session = special.get(date);
        return session == null ? REGULAR_OPEN : session.open();
    }

    /** 그날 정규장 마감 시각(벽시계)이에요. 종가 단일가는 이 시각부터 체결돼요. */
    public static LocalTime close(LocalDate date) {
        Session session = special.get(date);
        return session == null ? REGULAR_CLOSE : session.close();
    }

    /** 그날 마감이 평소보다 늦춰진 시간이에요(평소 0, 수능일 1시간). */
    public static Duration shift(LocalDate date) {
        return Duration.between(REGULAR_CLOSE, close(date));
    }

    /** 그날 개장을 평소 시각으로 바꾼 값이에요. 수능일(10:00~16:30)은 09:00, 개장만 늦춘 날(10:00~15:30)은 10:00이에요. */
    public static LocalTime regularOpen(LocalDate date) {
        return open(date).minus(shift(date));
    }

    /** 하는 일: 평소 시각을 그날 실제 시각(Instant)으로 바꿔요. 예: 수능일 15:15 → 16:15. */
    public static Instant at(LocalDate date, LocalTime regular) {
        return date.atTime(regular).plus(shift(date)).atZone(ZONE).toInstant();
    }

    /**
     * 하는 일: 실제 시각을 그날의 평소 시각으로 바꿔요. 예: 수능일 16:15 → 15:15.
     * 수능일 00:00~01:00처럼 빼면 전날로 넘어가는 시각은 00:00으로 둬요. 23시대로 바뀌면 "18:00 이후" 같은 조건이
     * 새벽에 잘못 참이 되기 때문이에요.
     */
    public static LocalTime regularTime(Instant instant) {
        var local = instant.atZone(ZONE);
        var regular = local.toLocalDateTime().minus(shift(local.toLocalDate()));
        return regular.toLocalDate().isBefore(local.toLocalDate()) ? LocalTime.MIDNIGHT : regular.toLocalTime();
    }

    /**
     * 하는 일: 고정 시각 작업이 지금 그날의 해당 평소 시각에 깨어난 것인지 확인해요.
     * cron을 평소 시각과 한 시간 뒤에 두 번 걸고, 이 확인을 통과한 쪽만 실제로 일해요.
     * 받는 것: 현재 시각, 평소 기준 실행 시각. 돌려주는 것: 평소 시각 기준으로 [실행 시각, 실행 시각 + 10분) 안이면 true.
     */
    public static boolean firesAt(Instant now, LocalTime regular) {
        LocalTime time = regularTime(now);
        return !time.isBefore(regular) && time.isBefore(regular.plus(FIRE_TOLERANCE));
    }
}
