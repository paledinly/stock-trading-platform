package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.CandidateResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.RegimeResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.RankingSourceResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.PrecisionAllocationResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.PrecisionAllocationItemResponse;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.UniverseResponse;
import com.sunmo.stockplatform.marketwide.domain.BroadCandidate;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.marketwide.domain.BroadQuoteData;
import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.CollectionSummary;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MarketWideScannerService {
    private final StockRepository stocks;
    private final BroadQuoteResolver quotes;
    private final BroadCollectionProperties properties;
    private final KisRequestExecutor requests;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final BroadCandidateCollector collector;
    private final BroadSnapshotService broadSnapshots;
    private final PrecisionSubscriptionAllocator precision;
    private final BroadEnrichmentQueue enrichment;

    public MarketWideScannerService(StockRepository stocks, BroadQuoteResolver quotes,
            RealtimeSubscriptionRegistry subscriptions, BroadCandidateCollector collector,
            BroadSnapshotService broadSnapshots, PrecisionSubscriptionAllocator precision,
            BroadCollectionProperties properties, KisRequestExecutor requests, BroadEnrichmentQueue enrichment) {
        this.stocks = stocks;
        this.quotes = quotes;
        this.subscriptions = subscriptions;
        this.collector = collector;
        this.broadSnapshots = broadSnapshots;
        this.precision = precision;
        this.properties = properties;
        this.requests = requests;
        this.enrichment = enrichment;
    }

    public BroadScanResponse scan(Market market, int limit, int candidates, boolean includeEtf) {
        int safeLimit = Math.min(Math.max(limit, 1), 120);
        int safeCandidates = Math.min(Math.max(candidates, 1), 30);
        BroadCandidateCollector.Result collected = collector.collect(market, safeLimit, includeEtf);
        Instant scannedAt = Instant.now();
        Map<String, BroadCandidate> byCode = collected.candidates().stream()
                .limit(safeLimit)
                .collect(Collectors.toMap(item -> item.stock().getStockCode(), item -> item,
                        (left, right) -> left, LinkedHashMap::new));
        List<QuoteAttempt> attempts = new ArrayList<>();
        int restLookups = 0;
        int restFailures = 0;
        for (BroadCandidate broad : byCode.values()) {
            var resolved = quotes.resolve(broad, restLookups < properties.detailQuoteBudget());
            if (resolved.restAttempted()) {
                restLookups++;
                if (resolved.error() != null) restFailures++;
            }
            BigDecimal score = resolved.data() != null && resolved.data().complete()
                    ? combinedScore(resolved.data(), broad) : rankingScore(broad);
            attempts.add(new QuoteAttempt(broad, resolved.data(), score, resolved.error()));
        }
        List<BroadSnapshotService.Capture> captures = attempts.stream()
                .map(attempt -> new BroadSnapshotService.Capture(attempt.broad(), null,
                        attempt.score(), attempt.error(), attempt.quote()))
                .toList();
        Map<String, MarketBroadSnapshot> persisted = broadSnapshots.save(scannedAt, captures);
        enrichment.enqueue(captures);
        List<BroadQuoteData> snapshots = attempts.stream().map(QuoteAttempt::quote)
                .filter(data -> data != null && data.complete())
                .toList();
        List<CandidateResponse> shortlisted = attempts.stream()
                .filter(attempt -> attempt.quote() != null && attempt.quote().complete())
                .map(attempt -> candidate(attempt, persisted.get(attempt.broad().stock().getStockCode())))
                .sorted(Comparator.comparing(CandidateResponse::broadScore).reversed())
                .limit(safeCandidates)
                .toList();
        PrecisionSubscriptionAllocator.Snapshot allocation = precision.reconcile(shortlisted.stream()
                .map(candidate -> new PrecisionSubscriptionAllocator.Candidate(candidate.stockCode(),
                        candidate.broadScore()))
                .toList());
        return new BroadScanResponse(
                scannedAt,
                market == null ? "ALL" : market.name(),
                safeLimit,
                snapshots.size(),
                shortlisted.size(),
                collected.fallback(),
                collected.sources().stream()
                        .map(source -> new RankingSourceResponse(source.type().name(), source.success(),
                                source.candidateCount(), source.error()))
                        .toList(),
                allocation(allocation),
                new UniverseResponse(
                        stocks.countByActiveTrue(),
                        stocks.countByActiveTrueAndManagedFalseAndTradingHaltedFalse(),
                        subscriptions.limit(),
                        subscriptions.all().size(),
                        subscriptions.remaining()),
                regime(snapshots),
                shortlisted,
                new CollectionSummary(properties.detailQuoteBudget(), restLookups, restFailures,
                        attempts.size() - snapshots.size(), sourceCounts(attempts), snapshots.stream()
                            .mapToLong(row -> Math.max(0, java.time.Duration.between(row.observedAt(), Instant.now()).toSeconds()))
                            .max().orElse(0), requests.diagnostics()));
    }

    private PrecisionAllocationResponse allocation(PrecisionSubscriptionAllocator.Snapshot snapshot) {
        return new PrecisionAllocationResponse(snapshot.state(), snapshot.evaluatedAt(), snapshot.capacity(),
                snapshot.activeCount(), snapshot.remainingSlots(), snapshot.reservedSlots(),
                snapshot.allocations().stream().map(item -> new PrecisionAllocationItemResponse(item.stockCode(),
                        item.score(), item.addedAt(), item.lastSeenAt(), item.awaitingAcknowledgement())).toList());
    }

    private Map<String, Integer> sourceCounts(List<QuoteAttempt> attempts) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        attempts.forEach(row -> counts.merge(row.quote() == null ? "NONE" : row.quote().source(), 1, Integer::sum));
        return counts;
    }

    private CandidateResponse candidate(QuoteAttempt attempt, MarketBroadSnapshot snapshot) {
        BroadQuoteData quote = attempt.quote();
        BroadCandidate broad = attempt.broad();
        List<String> sources = broad.ranks().keySet().stream().map(Enum::name).sorted().toList();
        String reason = sources.isEmpty() ? "FALLBACK_SAMPLE" : String.join("+", sources);
        Map<String, Integer> ranks = broad.ranks().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
        return new CandidateResponse(
                broad.stock().getStockCode(),
                broad.stock().getStockName(),
                broad.stock().getMarket().name(),
                quote.price(),
                quote.changeRate(),
                quote.volume(),
                quote.tradingValue(),
                attempt.score(),
                reason,
                sources,
                ranks,
                snapshot == null ? null : snapshot.getId(),
                snapshot == null ? "INSUFFICIENT" : snapshot.getDataQuality().name(),
                subscriptions.remaining() > 0 && !subscriptions.all().contains(broad.stock().getStockCode()),
                quote.observedAt(), quote.source());
    }

    static BigDecimal combinedScore(BroadQuoteData quote, BroadCandidate broad) {
        return score(quote).multiply(bd("0.65")).add(rankingScore(broad).multiply(bd("0.35")))
                .setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal rankingScore(BroadCandidate broad) {
        return broad.rankingScore().divide(bd("17"), 6, RoundingMode.HALF_UP).min(bd("100"));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static BigDecimal score(BroadQuoteData quote) {
        BigDecimal momentum = positive(quote.changeRate()).multiply(bd("12")).min(bd("45"));
        BigDecimal liquidity = quote.tradingValue()
                .divide(bd("100000000"), 6, RoundingMode.HALF_UP)
                .min(bd("35"));
        BigDecimal rangePosition = quote.high() == null || quote.low() == null || quote.high().compareTo(quote.low()) <= 0
                ? BigDecimal.ZERO
                : quote.price().subtract(quote.low())
                        .divide(quote.high().subtract(quote.low()), 6, RoundingMode.HALF_UP)
                        .multiply(bd("20")).max(BigDecimal.ZERO).min(bd("20"));
        return momentum.add(liquidity).add(rangePosition).min(bd("100")).setScale(3, RoundingMode.HALF_UP);
    }

    private RegimeResponse regime(List<BroadQuoteData> quotes) {
        long up = quotes.stream().filter(quote -> quote.changeRate().signum() > 0).count();
        long down = quotes.stream().filter(quote -> quote.changeRate().signum() < 0).count();
        BigDecimal averageChange = avg(quotes.stream().map(BroadQuoteData::changeRate).toList());
        BigDecimal averageValue = avg(quotes.stream().map(BroadQuoteData::tradingValue).toList());
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

    private static BigDecimal positive(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record QuoteAttempt(BroadCandidate broad, BroadQuoteData quote, BigDecimal score, String error) {
    }
}
