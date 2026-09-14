package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;
import com.sunmo.stockplatform.kis.ranking.MarketRankingProvider;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.marketwide.application.BroadCandidateCollector;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BroadCandidateCollectorTest {
    private final MarketRankingProvider rankings = mock(MarketRankingProvider.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final BroadCandidateCollector collector = new BroadCandidateCollector(rankings, stocks,
            new com.sunmo.stockplatform.market.config.BroadCollectionProperties(100, 30, null, null, true));

    @Test
    void mergesSourcesFiltersTradabilityAndContinuesAfterPartialFailure() {
        Stock samsung = stock("005930", "삼성전자", false, false);
        Stock managed = stock("123456", "관리종목", true, false);
        when(rankings.fetch(any(), any(), anyInt())).thenReturn(List.of());
        when(rankings.fetch(eq(RankingType.TURNOVER), eq(Market.KOSPI), anyInt()))
                .thenReturn(List.of(entry("005930", 2), entry("123456", 1)));
        when(rankings.fetch(eq(RankingType.VOLUME), eq(Market.KOSPI), anyInt()))
                .thenReturn(List.of(entry("005930", 5)));
        when(rankings.fetch(eq(RankingType.TRADE_STRENGTH), eq(Market.KOSPI), anyInt()))
                .thenThrow(new IllegalStateException("temporary failure"));
        when(stocks.findByStockCodeIn(any())).thenReturn(List.of(samsung, managed));

        var result = collector.collect(Market.KOSPI, 40, false);

        assertThat(result.fallback()).isFalse();
        assertThat(result.candidates()).extracting(item -> item.stock().getStockCode())
                .containsExactly("005930");
        assertThat(result.candidates().getFirst().ranks())
                .containsEntry(RankingType.TURNOVER, 2)
                .containsEntry(RankingType.VOLUME, 5);
        assertThat(result.candidates().getFirst().observations()).hasSize(2);
        assertThat(result.sources()).filteredOn(source -> !source.success())
                .extracting(source -> source.type()).containsExactly(RankingType.TRADE_STRENGTH);
    }

    @Test
    void fallsBackToExistingUniverseOnlyWhenEveryRankingIsEmptyOrFailed() {
        Stock samsung = stock("005930", "삼성전자", false, false);
        when(rankings.fetch(any(), any(), anyInt())).thenThrow(new IllegalStateException("offline"));
        when(stocks.broadScanUniverse(eq(null), eq(false), any())).thenReturn(List.of(samsung));

        var result = collector.collect(null, 40, false);

        assertThat(result.fallback()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.sources()).allMatch(source -> !source.success());
    }

    private KisRankingEntry entry(String code, int rank) {
        return new KisRankingEntry(code, code, rank, null, null, 0, null, null);
    }

    @Test
    void excludesStructuredNamesBeforeDetailLookupEvenWhenMasterFlagsAreMissing() {
        when(rankings.fetch(any(), any(), anyInt())).thenReturn(List.of(entry("005930", 1), entry("123456", 2)));
        when(stocks.findByStockCodeIn(any())).thenReturn(List.of(stock("005930", "삼성전자", false, false),
                stock("123456", "미래에셋 인버스 2X ETN", false, false)));
        assertThat(collector.collect(null, 40, false).candidates()).hasSize(1);
        assertThat(collector.collect(null, 40, true).candidates()).hasSize(2);
    }

    private Stock stock(String code, String name, boolean managed, boolean halted) {
        return new Stock(code, "KR7" + code + "003", name, Market.KOSPI, MarketType.STOCK,
                managed, halted, Instant.now());
    }
}
