package com.sunmo.stockplatform.candle.application;

import java.time.*;
import java.util.*;

public class CandleGapDetector {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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

    static Instant floorFiveMinutes(Instant value) {
        ZonedDateTime time = value.atZone(SEOUL).withSecond(0).withNano(0);
        return time.withMinute((time.getMinute() / 5) * 5).toInstant();
    }
}
