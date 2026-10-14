/*
 * KIS 랭킹 API(거래대금·거래량·상승률·체결강도·신고가 근접 순위표)를 모두 불러 BROAD 후보 목록을 만드는 파일이에요.
 * 여러 순위표에 높게 오른 종목일수록 랭킹 점수가 커지고, 순위표를 하나도 못 받으면 DB 종목 목록으로 대체(fallback)해요.
 * MarketWideScannerService가 시장 전체 스캔의 첫 단계로 사용해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;
import com.sunmo.stockplatform.kis.ranking.MarketRankingProvider;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.marketwide.domain.BroadCandidate;
import com.sunmo.stockplatform.marketwide.domain.StructuredProductFilter;
import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 여러 랭킹(순위표)을 모아 BROAD 후보를 뽑는 수집기예요.
 * 여러 신문의 "이번 주 인기 순위"를 모두 펼쳐 놓고, 여러 곳에 자주·높게 나온 이름에 점수를 더 주는 사람과 같아요.
 */
@Component
public class BroadCandidateCollector {
    private final MarketRankingProvider rankings;
    private final StockRepository stocks;
    private final BroadCollectionProperties properties;

    /**
     * 하는 일: 랭킹 제공자, 종목 레포지토리(DB 창구), 수집 설정을 받아 둬요.
     * 받는 것: 위 세 협력 객체.
     */
    public BroadCandidateCollector(MarketRankingProvider rankings, StockRepository stocks, BroadCollectionProperties properties) {
        this.rankings = rankings;
        this.stocks = stocks;
        this.properties = properties;
    }

    /**
     * 하는 일: 모든 랭킹 종류를 조회해 후보를 모으고 랭킹 점수 순으로 정렬해요.
     * 받는 것: 시장(null이면 전체), 대체 목록 최대 개수, ETF 포함 여부.
     * 돌려주는 것: 후보 목록, 랭킹 출처별 성공/실패 상태, 대체 목록을 썼는지 여부를 담은 Result.
     */
    public Result collect(Market market, int fallbackLimit, boolean includeEtf) {
        Map<String, EnumMap<RankingType, Integer>> ranksByCode = new LinkedHashMap<>();
        Map<String, BigDecimal> tradeStrengthByCode = new HashMap<>();
        Map<String, List<BroadCandidate.RankingObservation>> observations = new HashMap<>();
        List<SourceStatus> statuses = new ArrayList<>();
        // 처리 1: 랭킹 종류마다 조회해서 종목별 순위, 체결강도, 원본 관측값(받은 시각 포함)을 모아요.
        // 한 랭킹이 실패해도 나머지는 계속 조회하고, 실패 사유만 상태에 남겨요.
        for (RankingType type : RankingType.values()) {
            try {
                List<KisRankingEntry> entries = rankings.fetch(type, market, properties.rankingLimit());
                java.time.Instant receivedAt = java.time.Instant.now();
                for (KisRankingEntry entry : entries) {
                    observations.computeIfAbsent(entry.stockCode(), ignored -> new ArrayList<>())
                            .add(new BroadCandidate.RankingObservation(type, entry, receivedAt));
                    ranksByCode.computeIfAbsent(entry.stockCode(), ignored -> new EnumMap<>(RankingType.class))
                            .put(type, entry.rank());
                    // 체결강도는 여러 랭킹에 있으면 나중에 조회한 랭킹 값으로 덮어써요.
                    if (entry.tradeStrength() != null)
                        tradeStrengthByCode.put(entry.stockCode(), entry.tradeStrength());
                }
                statuses.add(new SourceStatus(type, true, entries.size(), null));
            } catch (RuntimeException error) {
                statuses.add(new SourceStatus(type, false, 0, rootMessage(error)));
            }
        }

        // 처리 2: 랭킹을 하나도 못 받았으면 DB의 스캔 대상 종목으로 대체 후보를 만들어요(점수 0).
        if (ranksByCode.isEmpty()) {
            List<BroadCandidate> fallback = stocks
                    .broadScanUniverse(market, includeEtf, PageRequest.of(0, fallbackLimit)).stream()
                    .filter(stock -> tradable(stock, market, includeEtf))
                    .map(stock -> new BroadCandidate(stock, BigDecimal.ZERO, Map.of(), null))
                    .toList();
            return new Result(fallback, List.copyOf(statuses), true);
        }

        // 결과: DB에 있는 거래 가능 종목만 남기고, 랭킹 점수 높은 순(같으면 종목코드 순)으로 정렬해요.
        Map<String, EnumMap<RankingType, Integer>> source = ranksByCode;
        List<BroadCandidate> candidates = stocks.findByStockCodeIn(source.keySet()).stream()
                .filter(stock -> tradable(stock, market, includeEtf))
                .map(stock -> candidate(stock, source.get(stock.getStockCode()),
                        tradeStrengthByCode.get(stock.getStockCode()), observations.get(stock.getStockCode())))
                .sorted(Comparator.comparing(BroadCandidate::rankingScore).reversed()
                        .thenComparing(item -> item.stock().getStockCode()))
                .toList();
        return new Result(candidates, List.copyOf(statuses), false);
    }

    /**
     * 하는 일: 한 종목의 랭킹 점수를 계산해 후보 객체로 만들어요.
     * 점수 = 랭킹별 (가중치 × max(1, 101 − 순위))의 합이에요. 1위는 100점, 100위 밖은 1점으로 쳐요.
     * 받는 것: 종목, 랭킹별 순위, 체결강도, 원본 관측값 목록.
     * 돌려주는 것: BROAD 후보.
     */
    private BroadCandidate candidate(Stock stock, EnumMap<RankingType, Integer> ranks, BigDecimal tradeStrength,
            List<BroadCandidate.RankingObservation> observations) {
        BigDecimal score = ranks.entrySet().stream()
                .map(entry -> BigDecimal.valueOf(weight(entry.getKey()) * Math.max(1, 101 - entry.getValue())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BroadCandidate(stock, score, Map.copyOf(ranks), tradeStrength, List.copyOf(observations));
    }

    /**
     * 하는 일: 랭킹 종류별 가중치를 정해요. 거래대금이 가장 크고, 신고가 근접이 가장 작아요.
     * 받는 것: 랭킹 종류.
     * 돌려주는 것: 가중치 정수.
     */
    private int weight(RankingType type) {
        return switch (type) {
            case TURNOVER -> 5;
            case VOLUME -> 3;
            case PRICE_RISE -> 3;
            case TRADE_STRENGTH -> 4;
            case HIGH_PROXIMITY -> 2;
        };
    }

    /**
     * 하는 일: 후보로 삼아도 되는 종목인지 걸러요.
     * 활성 상태이고, 관리종목·거래정지가 아니고, 시장이 맞아야 해요.
     * ETF를 빼는 경우엔 ETF/ETN과 (설정이 켜져 있으면) 이름상 구조화 상품도 빼요.
     * 받는 것: 종목, 시장(null이면 전체), ETF 포함 여부.
     * 돌려주는 것: 후보 가능하면 true.
     */
    private boolean tradable(Stock stock, Market market, boolean includeEtf) {
        return stock.isActive() && !stock.isManaged() && !stock.isTradingHalted()
                && (market == null || stock.getMarket() == market)
                && (includeEtf || (!stock.isEtf() && !stock.isEtn()
                    && (!properties.excludeStructuredNames() || !StructuredProductFilter.matches(stock.getStockName()))));
    }

    /**
     * 하는 일: 예외의 가장 안쪽 원인 메시지를 꺼내요.
     * 받는 것: 발생한 예외.
     * 돌려주는 것: 원인 메시지(비어 있으면 예외 클래스 이름).
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 수집 결과예요. candidates는 정렬된 후보, sources는 랭킹별 상태, fallback은 DB 대체 목록을 썼는지예요.
     */
    public record Result(List<BroadCandidate> candidates, List<SourceStatus> sources, boolean fallback) {
    }

    /**
     * 랭킹 하나의 조회 결과 상태예요. 성공 여부, 받은 종목 수, 실패 사유를 담아요.
     */
    public record SourceStatus(RankingType type, boolean success, int candidateCount, String error) {
    }
}
