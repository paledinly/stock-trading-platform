package com.sunmo.stockplatform.kis.websocket;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
@Component
public class KisRealtimeTickParser {private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HHmmss");private final AtomicLong sequence=new AtomicLong();public MarketTick parse(String payload){String[] f=payload.split("\\^",-1);if(f.length<15)throw new IllegalArgumentException("Unexpected H0STCNT0 field count: "+f.length);LocalDate date=LocalDate.now(SEOUL);LocalTime time=LocalTime.parse(f[1],TIME);Instant occurred=date.atTime(time).atZone(SEOUL).toInstant();return new MarketTick(f[0],date,occurred,decimal(f[2]),number(f[12]),number(f[13]),decimal(f[14]),sequence.incrementAndGet());}private BigDecimal decimal(String value){return new BigDecimal(value.trim());}private long number(String value){return Long.parseLong(value.trim());}}
