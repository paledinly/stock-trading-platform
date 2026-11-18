/*
 * 순위표 대신 장 전 변동성 명단 + 오늘 상승률 상위로 시장 전체 스캔 후보를 만드는 방식(ADR-013)을 확인해요.
 */
package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisRequestProperties;
import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;
import com.sunmo.stockplatform.kis.ranking.MarketRankingProvider;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.market.config.VolatilityUniverseProperties;
import com.sunmo.stockplatform.marketwide.application.*;
import com.sunmo.stockplatform.marketwide.domain.BroadCandidate;
import com.sunmo.stockplatform.quote.application.QuoteProvider;
import com.sunmo.stockplatform.quote.application.QuoteSnapshotCache;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 장 전 변동성 명단 방식의 후보 수집과 시세 갱신 순서를 점검해요. */
class VolatilitySubscriptionTest {
    private final VolatilityUniverseProperties volatilityProperties =
            new VolatilityUniverseProperties(true, 300, 20, null, 30, null, null);

    /**
     * 명단이 있으면 오늘 상승률 종목을 앞에, 명단 종목을 변동성 순서로 뒤에 두고, 다른 순위표는 부르지 않는지 확인해요.
     * 준비: 명단 [A, B], 코스피 상승률 [B 1위, C 2위], 코스닥 상승률 없음.
     * 기대: B(명단+상승), C(상승), A(명단) 순서, 장 전 명단 수 2, 거래대금·거래량 순위표는 부르지 않아요.
     */
    @Test
    void premarketListPlusTodaysGainersReplacesRankingMerge() {
        var rankings = mock(MarketRankingProvider.class);
        var stocks = mock(StockRepository.class);
        var universe = mock(VolatilityUniverse.class);
        Stock a = stock("111110", "에이"), b = stock("222220", "비"), c = stock("333330", "씨");
        when(universe.today()).thenReturn(List.of(a, b));
        when(rankings.fetch(eq(RankingType.PRICE_RISE), eq(Market.KOSPI), anyInt()))
                .thenReturn(List.of(entry("222220", 1), entry("333330", 2)));
        when(rankings.fetch(eq(RankingType.PRICE_RISE), eq(Market.KOSDAQ), anyInt())).thenReturn(List.of());
        when(stocks.findByStockCodeIn(any())).thenReturn(List.of(b, c));
        var collector = new BroadCandidateCollector(rankings, stocks,
                new BroadCollectionProperties(100, 30, null, null, true, null), universe, volatilityProperties);

        var result = collector.collect(null, 120, false);

        assertThat(result.premarketCount()).isEqualTo(2);
        assertThat(result.fallback()).isFalse();
        assertThat(result.candidates()).extracting(item -> item.stock().getStockCode())
                .containsExactly("222220", "333330", "111110");
        assertThat(result.candidates()).extracting(BroadCandidate::origin)
                .containsExactly("VOLATILITY_20D+PRICE_RISE_TODAY", "PRICE_RISE_TODAY", "VOLATILITY_20D");
        verify(rankings, never()).fetch(eq(RankingType.TURNOVER), any(), anyInt());
        verify(rankings, never()).fetch(eq(RankingType.TRADE_STRENGTH), any(), anyInt());
    }

    /** 명단이 비면(계산 실패 등) 기존 순위표 방식으로 돌아가는지 확인해요. */
    @Test
    void fallsBackToRankingsWhenPremarketListIsEmpty() {
        var rankings = mock(MarketRankingProvider.class);
        var stocks = mock(StockRepository.class);
        var universe = mock(VolatilityUniverse.class);
        when(universe.today()).thenReturn(List.of());
        when(rankings.fetch(any(), any(), anyInt())).thenReturn(List.of(entry("222220", 1)));
        when(stocks.findByStockCodeIn(any())).thenReturn(List.of(stock("222220", "비")));
        var collector = new BroadCandidateCollector(rankings, stocks,
                new BroadCollectionProperties(100, 30, null, null, true, null), universe, volatilityProperties);

        var result = collector.collect(null, 120, false);

        assertThat(result.premarketCount()).isZero();
        assertThat(result.candidates().getFirst().origin()).isNull();
        verify(rankings).fetch(eq(RankingType.TURNOVER), any(), anyInt());
    }

    /** 우선주·스팩·ETF는 명단에 넣지 않는지 확인해요. */
    @Test
    void premarketListKeepsOnlyCommonOperatingStocks() {
        assertThat(VolatilityUniverse.eligible(stock("005930", "삼성전자"))).isTrue();
        assertThat(VolatilityUniverse.eligible(stock("005935", "삼성전자우"))).isFalse();
        assertThat(VolatilityUniverse.eligible(stock("444440", "하나스팩30호"))).isFalse();
        assertThat(VolatilityUniverse.eligible(new Stock("069500", "KR7069500007", "KODEX 200", Market.KOSPI,
                MarketType.ETF, false, false, Instant.now()))).isFalse();
    }

    /**
     * 명단 방식은 시세가 없는 종목부터 REST로 받고, 몇 분 지난 캐시 시세는 버리지 않고 점수에 쓰는지 확인해요.
     * 준비: 명단 3종목, REST 예산 1. 0번은 방금 받은 캐시, 1번은 5분 전 캐시, 2번은 시세 없음.
     * 기대: REST는 2번 한 번만, 세 종목 모두 시세 점수가 있어 최종 후보 3개예요.
     */
    @Test
    void refreshesMissingQuotesFirstAndKeepsRecentCache() {
        var stocks = mock(StockRepository.class);
        var provider = mock(QuoteProvider.class);
        var cache = new QuoteSnapshotCache();
        var collector = mock(BroadCandidateCollector.class);
        var persistence = mock(BroadSnapshotService.class);
        var precision = mock(PrecisionSubscriptionAllocator.class);
        var properties = new BroadCollectionProperties(100, 1, null, null, true, null);
        var resolver = new BroadQuoteResolver(provider, mock(QuoteStateStore.class), cache, properties);
        var service = new MarketWideScannerService(stocks, resolver, mock(RealtimeSubscriptionRegistry.class), collector,
                persistence, precision, properties,
                new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0)),
                mock(BroadEnrichmentQueue.class), volatilityProperties);
        Stock s0 = stock("111110", "영"), s1 = stock("222220", "일"), s2 = stock("333330", "이");
        Instant now = Instant.now();
        cache.remember(quote(s0, now.minusSeconds(5)));
        cache.remember(quote(s1, now.minus(Duration.ofMinutes(5))));
        List<BroadCandidate> candidates = List.of(s0, s1, s2).stream()
                .map(s -> new BroadCandidate(s, BigDecimal.ZERO, Map.of(), null, List.of(), "VOLATILITY_20D")).toList();
        when(collector.collect(null, 120, false)).thenReturn(new BroadCandidateCollector.Result(candidates, List.of(), false, 3));
        when(provider.getQuote(any())).thenAnswer(call -> quote(call.getArgument(0), Instant.now()));
        when(persistence.save(any(), anyList())).thenReturn(Map.of());
        when(precision.reconcile(anyList())).thenReturn(mock(PrecisionSubscriptionAllocator.Snapshot.class));

        var response = service.scan(null, 120, 41, false);

        verify(provider, times(1)).getQuote(s2);
        verify(provider, never()).getQuote(s0);
        verify(provider, never()).getQuote(s1);
        assertThat(response.collection().restLookups()).isEqualTo(1);
        assertThat(response.candidateCount()).isEqualTo(3);
        assertThat(response.candidates()).extracting(item -> item.reason()).containsOnly("VOLATILITY_20D");
    }

    /**
     * 장 시작 직후처럼 시세가 없는 종목이 평소 REST 예산보다 많으면, 빠진 종목은 missingQuoteBudget까지 한 번에 받는지 확인해요.
     * 준비: 명단 4종목, 평소 REST 예산 1. 0번은 5분 전 캐시, 1~3번은 시세 없음.
     * 기대: 예산 3(빠진 3종목)으로 1~3번만 받고 0번은 캐시를 써요. 일괄 예산을 0으로 끄면 평소 예산 1만 써요.
     */
    @Test
    void fetchesEveryMissingQuoteAtOnceButNotStaleOnes() {
        assertThat(burstScan(volatilityProperties)).isEqualTo(3);
        assertThat(burstScan(new VolatilityUniverseProperties(true, 300, 20, null, 30, null, 0))).isEqualTo(1);
    }

    /** 위 시험의 스캔 한 번을 돌리고 REST 횟수를 돌려줘요. */
    private int burstScan(VolatilityUniverseProperties volatility) {
        var provider = mock(QuoteProvider.class);
        var cache = new QuoteSnapshotCache();
        var collector = mock(BroadCandidateCollector.class);
        var persistence = mock(BroadSnapshotService.class);
        var precision = mock(PrecisionSubscriptionAllocator.class);
        var properties = new BroadCollectionProperties(100, 1, null, null, true, null);
        var resolver = new BroadQuoteResolver(provider, mock(QuoteStateStore.class), cache, properties);
        var service = new MarketWideScannerService(mock(StockRepository.class), resolver,
                mock(RealtimeSubscriptionRegistry.class), collector, persistence, precision, properties,
                new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0)),
                mock(BroadEnrichmentQueue.class), volatility);
        List<Stock> list = List.of(stock("111110", "영"), stock("222220", "일"), stock("333330", "이"), stock("444440", "삼"));
        cache.remember(quote(list.get(0), Instant.now().minus(Duration.ofMinutes(5))));
        List<BroadCandidate> candidates = list.stream()
                .map(s -> new BroadCandidate(s, BigDecimal.ZERO, Map.of(), null, List.of(), "VOLATILITY_20D")).toList();
        when(collector.collect(null, 120, false)).thenReturn(new BroadCandidateCollector.Result(candidates, List.of(), false, 4));
        when(provider.getQuote(any())).thenAnswer(call -> quote(call.getArgument(0), Instant.now()));
        when(persistence.save(any(), anyList())).thenReturn(Map.of());
        when(precision.reconcile(anyList())).thenReturn(mock(PrecisionSubscriptionAllocator.Snapshot.class));

        var response = service.scan(null, 120, 41, false);

        verify(provider, never()).getQuote(list.get(0));
        return response.collection().restLookups();
    }

    private static Stock stock(String code, String name) {
        Stock stock = new Stock(code, "KR7" + code + "003", name, Market.KOSPI, MarketType.STOCK, false, false, Instant.now());
        try {
            var id = Stock.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(stock, Long.parseLong(code));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
        return stock;
    }

    private static StockQuote quote(Stock stock, Instant at) {
        return new StockQuote(stock.getStockCode(), stock.getStockName(), "KOSPI", new BigDecimal("1000"),
                new BigDecimal("10"), new BigDecimal("1.0"), new BigDecimal("990"), new BigDecimal("1010"),
                new BigDecimal("980"), 100_000, new BigDecimal("100000000"), at);
    }

    private static KisRankingEntry entry(String code, int rank) {
        return new KisRankingEntry(code, code, rank, new BigDecimal("5000"), new BigDecimal("12.5"), 1_000_000L,
                new BigDecimal("5000000000"), null, null, null, null);
    }
}
