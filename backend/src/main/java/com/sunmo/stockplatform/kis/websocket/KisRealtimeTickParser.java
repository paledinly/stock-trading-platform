/*
 * KIS 실시간 체결(H0STCNT0) 메시지의 '^'로 구분된 본문을 MarketTick(체결 한 건) 목록으로 바꿔요.
 * 한 메시지에 여러 체결이 묶여 올 수 있어서, 체결 하나당 46개(최신 형식은 47개) 필드씩 잘라 읽어요.
 * KisRealtimeClient가 WebSocket으로 받은 체결 메시지를 해석할 때 사용해요.
 */
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

/**
 * KIS 실시간 체결 문자열을 읽어 체결 기록으로 바꾸는 해석기예요.
 * 영수증에 '^'로 이어 적힌 항목들을 칸 번호대로 읽어 장부 양식(MarketTick)에 옮겨 적는 경리 직원과 같아요.
 * 필수 칸(종목코드, 영업일, 체결시각, 가격, 거래량 등)이 비면 0으로 채우지 않고 실패로 처리해요.
 */
@Component
public class KisRealtimeTickParser {
    // 예전 H0STCNT0 형식의 체결 한 건당 필드 개수예요. 최신 형식은 끝에 시장 구분 코드가 하나 더 붙어요.
    static final int FIELDS_PER_TRADE = 46;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 해석한 체결마다 붙이는 프로세스 내부 일련번호예요. 같은 시각 체결의 들어온 순서를 구분하는 데 쓰는 것으로 보여요.
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 체결 한 건짜리 메시지를 해석해요.
     * 받는 것: '^'로 구분된 본문. 돌려주는 것: MarketTick 하나.
     */
    public MarketTick parse(String payload) {
        return parseMany(payload, 1).getFirst();
    }

    /**
     * 여러 체결이 묶인 메시지를 해석해요.
     * 받는 것: '^'로 구분된 본문(payload), 메시지 머리에 적힌 체결 건수(count).
     * 돌려주는 것: 순서가 유지된 MarketTick 목록. 필드 수가 건수와 맞지 않으면 IllegalArgumentException을 던져요.
     */
    public List<MarketTick> parseMany(String payload, int count) {
        if (count < 1)
            throw new IllegalArgumentException("H0STCNT0 trade count must be positive: " + count);
        // 준비: 끝의 빈 칸도 버리지 않도록 limit -1로 나눠요.
        String[] fields = payload.split("\\^", -1);
        int required = count * FIELDS_PER_TRADE;
        if (fields.length < required) {
            throw new IllegalArgumentException(
                    "Unexpected H0STCNT0 field count: expected at least " + required + ", got " + fields.length);
        }
        // Current KRX schema appends MARKET_CLS_CODE; retain legacy 46-field frames.
        int stride = fields.length == count * (FIELDS_PER_TRADE + 1)
                ? FIELDS_PER_TRADE + 1 : FIELDS_PER_TRADE;
        if (fields.length != count * stride)
            throw new IllegalArgumentException("Unexpected H0STCNT0 field count: " + fields.length);
        // 처리: 체결 한 건씩 시작 위치(offset)를 옮겨 가며 읽어요.
        List<MarketTick> ticks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ticks.add(parse(fields, index * stride, index));
        }
        return List.copyOf(ticks);
    }

    /**
     * 필드 배열에서 체결 한 건을 꺼내 MarketTick으로 만들어요.
     * 받는 것: 전체 필드 배열, 이 체결의 시작 위치(offset), 몇 번째 체결인지(record).
     * 돌려주는 것: MarketTick. 실패하면 몇 번째 체결에서 실패했는지 담아 IllegalArgumentException을 던져요.
     */
    private MarketTick parse(String[] fields, int offset, int record) {
        try {
            // 원천 발생시각: KIS가 준 영업일(33번 칸)과 체결시각(1번 칸)을 서울 시간으로 합쳐요. 서버 수신시각과는 다른 값이에요.
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
                    // 선택 칸: 체결강도(18), 누적 매도·매수 체결량(19·20), 매수비율(22)은 비어 있으면 null로 남겨요.
                    nullableDecimal(fields[offset + 18]), nullableNumber(fields[offset + 19]),
                    nullableNumber(fields[offset + 20]), nullableDecimal(fields[offset + 22]),
                    // 거래정지 여부(35), VI 발동 기준가(45), 거래량 회전율(40)이에요.
                    "Y".equalsIgnoreCase(fields[offset + 35].trim()),
                    nullableDecimal(fields[offset + 45]), nullableDecimal(fields[offset + 40]));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid H0STCNT0 record " + record + ": " + error.getMessage(), error);
        }
    }

    /**
     * 필수 칸 값을 꺼내요. 받는 것: 필드 배열, 칸 번호, 오류 메시지용 이름. 돌려주는 것: 공백을 지운 값.
     * 비어 있으면 예외를 던져요.
     */
    private String required(String[] fields, int index, String name) {
        String normalized = fields[index] == null ? "" : fields[index].trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is blank");
        return normalized;
    }

    /** 필수 칸을 숫자(BigDecimal)로 바꿔요. 비었거나 숫자가 아니면 예외를 던져요. */
    private BigDecimal requiredDecimal(String[] fields, int index, String name) {
        return new BigDecimal(required(fields, index, name));
    }

    /** 필수 칸을 정수(long)로 바꿔요. 비었거나 숫자가 아니면 예외를 던져요. */
    private long requiredLong(String[] fields, int index, String name) {
        return Long.parseLong(required(fields, index, name));
    }

    /** 선택 칸을 숫자로 바꿔요. 비어 있으면 0이 아니라 null을 돌려줘요. */
    private BigDecimal nullableDecimal(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : new BigDecimal(normalized);
    }

    /** 선택 칸을 정수로 바꿔요. 비어 있으면 0이 아니라 null을 돌려줘요. */
    private Long nullableNumber(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : Long.parseLong(normalized);
    }
}
