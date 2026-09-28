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
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AtomicLong sequence = new AtomicLong();

    public MarketTick parse(String payload) {
        return parseMany(payload, 1).getFirst();
    }

    public List<MarketTick> parseMany(String payload, int count) {
        if (count < 1)
            throw new IllegalArgumentException("H0STCNT0 trade count must be positive: " + count);
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
        int record = offset / FIELDS_PER_TRADE;
        try {
            LocalDate date = LocalDate.parse(required(fields, offset + 33, "businessDate"), DATE);
            LocalTime time = LocalTime.parse(required(fields, offset + 1, "tradeTime"), TIME);
            Instant occurredAt = date.atTime(time).atZone(SEOUL).toInstant();
            return new MarketTick(required(fields, offset, "stockCode"), date, occurredAt,
                    requiredDecimal(fields, offset + 2, "price"),
                    requiredLong(fields, offset + 12, "tradeVolume"),
                    requiredLong(fields, offset + 13, "cumulativeVolume"),
                    requiredDecimal(fields, offset + 14, "cumulativeTradingValue"), sequence.incrementAndGet(),
                    requiredDecimal(fields, offset + 7, "openPrice"),
                    requiredDecimal(fields, offset + 8, "highPrice"),
                    requiredDecimal(fields, offset + 9, "lowPrice"),
                    nullableDecimal(fields[offset + 18]), nullableNumber(fields[offset + 19]),
                    nullableNumber(fields[offset + 20]), nullableDecimal(fields[offset + 22]),
                    "Y".equalsIgnoreCase(fields[offset + 35].trim()),
                    nullableDecimal(fields[offset + 45]), nullableDecimal(fields[offset + 40]));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid H0STCNT0 record " + record + ": " + error.getMessage(), error);
        }
    }

    private String required(String[] fields, int index, String name) {
        String normalized = fields[index] == null ? "" : fields[index].trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is blank");
        return normalized;
    }

    private BigDecimal requiredDecimal(String[] fields, int index, String name) {
        return new BigDecimal(required(fields, index, name));
    }

    private long requiredLong(String[] fields, int index, String name) {
        return Long.parseLong(required(fields, index, name));
    }

    private BigDecimal nullableDecimal(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : new BigDecimal(normalized);
    }

    private Long nullableNumber(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : Long.parseLong(normalized);
    }
}
