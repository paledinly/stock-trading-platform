/*
 * 하루 정규장 5분봉 칸(09:00~15:25) 중 아직 저장되지 않은 칸을 찾아 주는 계산기예요.
 * CandleQueryService가 오늘 차트를 조회할 때 빈 칸을 찾고, CandleBackfillService가 5분 내림 계산(floorFiveMinutes)을 빌려 써요.
 */
package com.sunmo.stockplatform.candle.application;

import java.time.*;
import java.util.*;

/**
 * 빈 봉 찾기 도우미예요. 출석부에서 결석한 칸을 표시하듯, 09:00부터 5분 칸마다 저장된 봉이 있는지 확인해 없는 칸을 모아요.
 */
public class CandleGapDetector {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 하는 일: 주어진 날짜의 09:00부터 기준 시각 직전 5분 칸까지(장 마감 후면 15:25 칸까지) 빈 칸을 찾아요.
     * 진행 중인 현재 칸은 아직 끝나지 않았으므로 빈 칸으로 세지 않아요.
     * 받는 것: 날짜, 기준 시각(보통 지금), 이미 있는 봉 시작시각들.
     * 돌려주는 것: 빈 칸의 시작시각 목록(오래된 순).
     * ⚠️ 확인 필요: 15:20 칸은 종가 단일가 접수 시간이라 정상적으로 체결·봉이 없는데도 빈 칸으로 잡혀요.
     * 보충도 이 칸을 채우지 않으므로 장 마감 후 조회 때마다 빈 칸 1개가 남고 보충 요청이 반복될 수 있어요.
     */
    public List<Instant> gaps(LocalDate date, Instant through, Collection<Instant> existing) {
        Instant open = date.atTime(9, 0).atZone(SEOUL).toInstant();
        Instant close = date.atTime(15, 30).atZone(SEOUL).toInstant();
        Instant limit = through.isBefore(close) ? floorFiveMinutes(through) : close;
        Set<Instant> found = new HashSet<>(existing);
        List<Instant> gaps = new ArrayList<>();
        for (Instant bucket = open; bucket.isBefore(limit); bucket = bucket.plus(Duration.ofMinutes(5))) {
            if (!found.contains(bucket))
                gaps.add(bucket);
        }
        return gaps;
    }

    /**
     * 하는 일: 시각을 서울 기준 5분 단위로 내림해요(예: 10:07:30 → 10:05:00).
     * 받는 것: 시각. 돌려주는 것: 내림한 시각.
     */
    static Instant floorFiveMinutes(Instant value) {
        ZonedDateTime time = value.atZone(SEOUL).withSecond(0).withNano(0);
        return time.withMinute((time.getMinute() / 5) * 5).toInstant();
    }
}
