package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.quote.application.*;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class BroadQuoteResolver {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final QuoteProvider provider;
    private final QuoteStateStore ticks;
    private final QuoteSnapshotCache cache;
    private final BroadCollectionProperties properties;
    public BroadQuoteResolver(QuoteProvider provider, QuoteStateStore ticks, QuoteSnapshotCache cache,
            BroadCollectionProperties properties) {
        this.provider = provider; this.ticks = ticks; this.cache = cache; this.properties = properties;
    }
    public Resolution resolve(BroadCandidate candidate, boolean allowRest) {
        Instant now = Instant.now();
        String code = candidate.stock().getStockCode();
        BroadQuoteData ranking = candidate.observations().stream().map(BroadQuoteData::fromRanking)
                .filter(row -> fresh(row.observedAt(), now, properties.cacheMaxAge()))
                .max(Comparator.comparingInt(BroadQuoteData::availableCoreFields).thenComparing(BroadQuoteData::observedAt))
                .orElse(null);
        MarketTick tick = ticks.get(code).filter(row -> row.businessDate().equals(now.atZone(SEOUL).toLocalDate())
                && fresh(row.occurredAt(), now, properties.tickMaxAge())).orElse(null);
        StockQuote baseline = cache.today(code, now).orElse(null);
        if (tick != null && baseline != null && tick.occurredAt().isAfter(baseline.quotedAt())) {
            StockQuote merged = QuoteService.merge(baseline, tick);
            cache.remember(merged);
            return new Resolution(BroadQuoteData.fromQuote(merged, "REALTIME_CACHE"), null, false);
        }
        if (tick != null) {
            boolean samePrice = ranking != null && ranking.price() != null && ranking.price().compareTo(tick.price()) == 0;
            BroadQuoteData realtime = new BroadQuoteData(tick.price(), samePrice ? ranking.changeRate() : null,
                    tick.cumulativeVolume(), tick.cumulativeTradingValue(), tick.openPrice(), tick.highPrice(),
                    tick.lowPrice(), tick.tradeStrength(), samePrice && ranking.observedAt().isBefore(tick.occurredAt())
                            ? ranking.observedAt() : tick.occurredAt(), samePrice ? "REALTIME_RANKING" : "REALTIME_PARTIAL");
            if (realtime.complete()) return new Resolution(realtime, null, false);
            if (ranking == null) ranking = realtime;
        }
        StockQuote recent = cache.fresh(code, now, properties.cacheMaxAge()).orElse(null);
        if (recent != null) return new Resolution(BroadQuoteData.fromQuote(recent, "CACHE"), null, false);
        if (ranking != null && ranking.complete()) return new Resolution(ranking, null, false);
        if (!allowRest) return new Resolution(ranking, "DETAIL_QUOTE_BUDGET_EXHAUSTED", false);
        try {
            StockQuote quote = provider.getQuote(candidate.stock());
            cache.remember(quote);
            return new Resolution(BroadQuoteData.fromQuote(quote, "REST"), null, true);
        } catch (RuntimeException error) {
            return new Resolution(ranking, error.getMessage(), true);
        }
    }
    private boolean fresh(Instant at, Instant now, Duration age) {
        return at != null && !at.isAfter(now) && !at.isBefore(now.minus(age));
    }
    public record Resolution(BroadQuoteData data, String error, boolean restAttempted) { }
}
