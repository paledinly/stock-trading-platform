package com.sunmo.stockplatform.kis.websocket;

import com.sunmo.stockplatform.market.domain.MarketTick;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class KisRealtimeTickParser {
    static final int FIELDS_PER_TRADE = 46;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private final AtomicLong sequence = new AtomicLong();

    public MarketTick parse(String payload) {
        return parseMany(payload, 1).getFirst();
    }

    public List<MarketTick> parseMany(String payload, int count) {
        if (count < 1) throw new IllegalArgumentException("H0STCNT0 trade count must be positive: " + count);
        String[] fields = payload.split("\\^", -1);
        int required = count * FIELDS_PER_TRADE;
        if (fields.length < required) {
            throw new IllegalArgumentException(
                    "Unexpected H0STCNT0 field count: expected at least " + required + ", got " + fields.length);
        }
        List<MarketTick> ticks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ticks.add(parse(fields, index * FIELDS_PER_TRADE));
        }
        return List.copyOf(ticks);
    }

    private MarketTick parse(String[] fields, int offset) {
        LocalDate date = LocalDate.now(SEOUL);
        LocalTime time = LocalTime.parse(fields[offset + 1], TIME);
        Instant occurredAt = date.atTime(time).atZone(SEOUL).toInstant();
        return new MarketTick(fields[offset], date, occurredAt, decimal(fields[offset + 2]),
                number(fields[offset + 12]), number(fields[offset + 13]), decimal(fields[offset + 14]),
                sequence.incrementAndGet());
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value.trim());
    }

    private long number(String value) {
        return Long.parseLong(value.trim());
    }
}