package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Shared real REST/merged baselines; no subscriptions and no synthesized tick-only baselines. */
@Component
public class QuoteSnapshotCache {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Map<String, StockQuote> values = new ConcurrentHashMap<>();
    public Optional<StockQuote> today(String code, Instant now) {
        return Optional.ofNullable(values.get(code)).filter(quote -> quote.quotedAt() != null
                && !quote.quotedAt().isAfter(now)
                && quote.quotedAt().atZone(SEOUL).toLocalDate().equals(now.atZone(SEOUL).toLocalDate()));
    }
    public Optional<StockQuote> fresh(String code, Instant now, Duration maxAge) {
        return today(code, now).filter(quote -> !quote.quotedAt().isBefore(now.minus(maxAge)));
    }
    public void remember(StockQuote quote) {
        values.compute(quote.stockCode(), (code, previous) -> previous == null
                || quote.quotedAt().isAfter(previous.quotedAt()) ? quote : previous);
    }
}
