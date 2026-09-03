package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.CandidateResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.RegimeResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.UniverseResponse;
import com.sunmo.stockplatform.quote.application.QuoteProvider;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class MarketWideScannerService {
    private final StockRepository stocks;
    private final QuoteProvider quotes;
    private final RealtimeSubscriptionRegistry subscriptions;

    public MarketWideScannerService(StockRepository stocks, QuoteProvider quotes,
            RealtimeSubscriptionRegistry subscriptions) {
        this.stocks = stocks;
        this.quotes = quotes;
        this.subscriptions = subscriptions;
    }

    public BroadScanResponse scan(Market market, int limit, int candidates, boolean includeEtf) {
        int safeLimit = Math.min(Math.max(limit, 1), 120);
        int safeCandidates = Math.min(Math.max(candidates, 1), 30);
        var universe = stocks.broadScanUniverse(market, includeEtf, PageRequest.of(0, safeLimit));
        List<StockQuote> snapshots = universe.stream()
                .map(this::quote)
                .filter(Objects::nonNull)
                .toList();
        List<CandidateResponse> shortlisted = snapshots.stream()
                .map(this::candidate)
                .sorted(Comparator.comparing(CandidateResponse::broadScore).reversed())
                .limit(safeCandidates)
                .toList();
        return new BroadScanResponse(
                Instant.now(),
                market == null ? "ALL" : market.name(),
                safeLimit,
                snapshots.size(),
                shortlisted.size(),
                new UniverseResponse(
                        stocks.countByActiveTrue(),
                        stocks.countByActiveTrueAndManagedFalseAndTradingHaltedFalse(),
                        subscriptions.limit(),
                        subscriptions.all().size(),
                        subscriptions.remaining()),
                regime(snapshots),
                shortlisted);
    }

    private StockQuote quote(com.sunmo.stockplatform.stock.domain.Stock stock) {
        try {
            return quotes.getQuote(stock);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private CandidateResponse candidate(StockQuote quote) {
        BigDecimal score = score(quote);
        String reason = quote.changeRate().compareTo(BigDecimal.ZERO) >= 0
                ? "PRICE_STRENGTH"
                : "LIQUIDITY_ONLY";
        return new CandidateResponse(
                quote.stockCode(),
                quote.stockName(),
                quote.market(),
                quote.currentPrice(),
                quote.changeRate(),
                quote.accumulatedVolume(),
                quote.accumulatedTradingValue(),
                score,
                reason,
                subscriptions.remaining() > 0 && !subscriptions.all().contains(quote.stockCode()),
                quote.quotedAt());
    }

    private BigDecimal score(StockQuote quote) {
        BigDecimal momentum = positive(quote.changeRate()).multiply(bd("12")).min(bd("45"));
        BigDecimal liquidity = quote.accumulatedTradingValue()
                .divide(bd("100000000"), 6, RoundingMode.HALF_UP)
                .min(bd("35"));
        BigDecimal rangePosition = quote.highPrice().compareTo(quote.lowPrice()) == 0
                ? BigDecimal.ZERO
                : quote.currentPrice().subtract(quote.lowPrice())
                        .divide(quote.highPrice().subtract(quote.lowPrice()), 6, RoundingMode.HALF_UP)
                        .multiply(bd("20"));
        return momentum.add(liquidity).add(rangePosition).min(bd("100")).setScale(3, RoundingMode.HALF_UP);
    }

    private RegimeResponse regime(List<StockQuote> quotes) {
        long up = quotes.stream().filter(quote -> quote.changeRate().signum() > 0).count();
        long down = quotes.stream().filter(quote -> quote.changeRate().signum() < 0).count();
        BigDecimal averageChange = avg(quotes.stream().map(StockQuote::changeRate).toList());
        BigDecimal averageValue = avg(quotes.stream().map(StockQuote::accumulatedTradingValue).toList());
        BigDecimal advanceRate = rate(up, quotes.size());
        BigDecimal declineRate = rate(down, quotes.size());
        String state = averageChange == null ? "UNKNOWN"
                : averageChange.compareTo(bd("0.7")) >= 0 && advanceRate.compareTo(bd("55")) >= 0 ? "RISK_ON"
                        : averageChange.compareTo(bd("-0.7")) <= 0 && declineRate.compareTo(bd("55")) >= 0 ? "RISK_OFF"
                                : "MIXED";
        return new RegimeResponse(state, averageChange, advanceRate, declineRate, averageValue);
    }

    private BigDecimal avg(List<BigDecimal> values) {
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(long count, long total) {
        if (total == 0)
            return BigDecimal.ZERO;
        return BigDecimal.valueOf(count)
                .multiply(bd("100"))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal positive(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
