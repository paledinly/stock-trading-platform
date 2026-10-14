/*
 * 시장 전체 스캔을 언제, 얼마나 자주, 몇 종목까지 할지 정하는 설정값(market.wide.schedule.*)을 담아요.
 * 빈 값에는 기본값을 채우고, 스캔 개수(최대 120)와 후보 개수(최대 41)는 상한을 넘지 않게 잘라요.
 * marketwide 패키지의 스캔 스케줄러·MarketSessionPolicy와 ClosingTradingCalendar가 이 값을 읽어요.
 */
package com.sunmo.stockplatform.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.*;
import java.util.List;

/**
 * 시장 전체 스캔 시간표예요. 순찰 근무표처럼 "09:00~15:20 사이 평소엔 5분마다, 14:00 이후엔 2분마다" 돌도록 정해요.
 * scanLimit: 한 번에 훑을 종목 수(기본·최대 120), candidateLimit: 정밀 배정 후보로 넘길 수(기본·최대 41, KIS 구독 한도와 같아요).
 * holidays는 설정으로 추가하는 정적 휴장일 목록이에요. 공식 자동 달력이 아니라 ClosingTradingCalendar 정책의 보조 입력이에요.
 */
@ConfigurationProperties(prefix = "market.wide.schedule")
public record MarketWideScheduleProperties(boolean enabled, int scanLimit, int candidateLimit, boolean includeEtf,
        LocalTime open, LocalTime close, LocalTime lateStart, Duration normalInterval, Duration lateInterval,
        List<LocalDate> holidays) {
    /** 하는 일: 빈 값에 기본값을 채우고 개수 상한을 적용해요. 휴장일 목록은 바꿀 수 없는 복사본으로 만들어요. */
    public MarketWideScheduleProperties {
        scanLimit = scanLimit <= 0 ? 120 : Math.min(scanLimit, 120);
        candidateLimit = candidateLimit <= 0 ? 41 : Math.min(candidateLimit, 41);
        open = open == null ? LocalTime.of(9, 0) : open;
        close = close == null ? LocalTime.of(15, 20) : close;
        lateStart = lateStart == null ? LocalTime.of(14, 0) : lateStart;
        normalInterval = normalInterval == null ? Duration.ofMinutes(5) : normalInterval;
        lateInterval = lateInterval == null ? Duration.ofMinutes(2) : lateInterval;
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
    }
}
