package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;
import com.sunmo.stockplatform.kis.ranking.MarketRankingProvider;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.marketwide.domain.BroadCandidate;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class BroadCandidateCollector {
    private static final int RANKING_LIMIT = 100;
    private final MarketRankingProvider rankings;
    private final StockRepository stocks;

    public BroadCandidateCollector(MarketRankingProvider rankings, StockRepository stocks) {
        this.rankings = rankings;
        this.stocks = stocks;
    }

    public Result collect(Market market, int fallbackLimit, boolean includeEtf) {
        Map<String, EnumMap<RankingType, Integer>> ranksByCode = new LinkedHashMap<>();
        Map<String, BigDecimal> tradeStrengthByCode = new HashMap<>();
        List<SourceStatus> statuses = new ArrayList<>();
        for (RankingType type : RankingType.values()) {
            try {
                List<KisRankingEntry> entries = rankings.fetch(type, market, RANKING_LIMIT);
                for (KisRankingEntry entry : entries) {
                    ranksByCode.computeIfAbsent(entry.stockCode(), ignored -> new EnumMap<>(RankingType.class))
                            .put(type, entry.rank());
                    if (entry.tradeStrength() != null)
                        tradeStrengthByCode.put(entry.stockCode(), entry.tradeStrength());
                }
                statuses.add(new SourceStatus(type, true, entries.size(), null));
            } catch (RuntimeException error) {
                statuses.add(new SourceStatus(type, false, 0, rootMessage(error)));
            }
        }

        if (ranksByCode.isEmpty()) {
            List<BroadCandidate> fallback = stocks
                    .broadScanUniverse(market, includeEtf, PageRequest.of(0, fallbackLimit)).stream()
                    .map(stock -> new BroadCandidate(stock, BigDecimal.ZERO, Map.of(), null))
                    .toList();
            return new Result(fallback, List.copyOf(statuses), true);
        }

        Map<String, EnumMap<RankingType, Integer>> source = ranksByCode;
        List<BroadCandidate> candidates = stocks.findByStockCodeIn(source.keySet()).stream()
                .filter(stock -> tradable(stock, market, includeEtf))
                .map(stock -> candidate(stock, source.get(stock.getStockCode()),
                        tradeStrengthByCode.get(stock.getStockCode())))
                .sorted(Comparator.comparing(BroadCandidate::rankingScore).reversed()
                        .thenComparing(item -> item.stock().getStockCode()))
                .toList();
        return new Result(candidates, List.copyOf(statuses), false);
    }

    private BroadCandidate candidate(Stock stock, EnumMap<RankingType, Integer> ranks, BigDecimal tradeStrength) {
        BigDecimal score = ranks.entrySet().stream()
                .map(entry -> BigDecimal.valueOf(weight(entry.getKey()) * Math.max(1, 101 - entry.getValue())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BroadCandidate(stock, score, Map.copyOf(ranks), tradeStrength);
    }

    private int weight(RankingType type) {
        return switch (type) {
            case TURNOVER -> 5;
            case VOLUME -> 3;
            case PRICE_RISE -> 3;
            case TRADE_STRENGTH -> 4;
            case HIGH_PROXIMITY -> 2;
        };
    }

    private boolean tradable(Stock stock, Market market, boolean includeEtf) {
        return stock.isActive() && !stock.isManaged() && !stock.isTradingHalted()
                && (market == null || stock.getMarket() == market)
                && (includeEtf || (!stock.isEtf() && !stock.isEtn()));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record Result(List<BroadCandidate> candidates, List<SourceStatus> sources, boolean fallback) {
    }

    public record SourceStatus(RankingType type, boolean success, int candidateCount, String error) {
    }
}
