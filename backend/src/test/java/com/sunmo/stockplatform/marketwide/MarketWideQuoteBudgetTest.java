package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.kis.config.*;
import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.marketwide.application.*;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.quote.application.*;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.*;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MarketWideQuoteBudgetTest {
    @Test
    void capsDetailLookupsAcrossWholeScanAndKeepsRemainingCandidates() {
        var stocks = mock(StockRepository.class);
        var provider = mock(QuoteProvider.class);
        var ticks = mock(QuoteStateStore.class);
        var collector = mock(BroadCandidateCollector.class);
        var persistence = mock(BroadSnapshotService.class);
        var precision = mock(PrecisionSubscriptionAllocator.class);
        var subscriptions = mock(RealtimeSubscriptionRegistry.class);
        var properties = new BroadCollectionProperties(100, 2, null, null, true);
        var resolver = new BroadQuoteResolver(provider, ticks, new QuoteSnapshotCache(), properties);
        var executor = new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0));
        var service = new MarketWideScannerService(stocks, resolver, subscriptions, collector, persistence, precision,
                properties, executor, mock(BroadEnrichmentQueue.class));
        List<BroadCandidate> candidates = java.util.stream.IntStream.range(0, 5).mapToObj(index -> {
            Stock stock = mock(Stock.class);
            when(stock.getId()).thenReturn((long) index + 1);
            when(stock.getStockCode()).thenReturn("00000" + index);
            when(stock.getStockName()).thenReturn("stock" + index);
            when(stock.getMarket()).thenReturn(Market.KOSPI);
            return new BroadCandidate(stock, BigDecimal.ZERO, Map.of(), null);
        }).toList();
        when(collector.collect(null, 5, false)).thenReturn(new BroadCandidateCollector.Result(candidates, List.of(), true));
        when(provider.getQuote(any())).thenAnswer(call -> {
            Stock stock = call.getArgument(0);
            return new StockQuote(stock.getStockCode(), stock.getStockName(), "KOSPI", new BigDecimal("1000"),
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("1000"),
                    new BigDecimal("1000"), 100, new BigDecimal("100000"), Instant.now());
        });
        when(persistence.save(any(), anyList())).thenReturn(Map.of());
        when(precision.reconcile(anyList())).thenReturn(mock(PrecisionSubscriptionAllocator.Snapshot.class));
        var response = service.scan(null, 5, 5, false);
        assertThat(response.collection().restLookups()).isEqualTo(2);
        assertThat(response.collection().insufficientCount()).isEqualTo(3);
        assertThat(response.scannedCount()).isEqualTo(2);
        verify(provider, times(2)).getQuote(any());
        verify(subscriptions, never()).add(anyString());
        org.mockito.ArgumentCaptor<List<BroadSnapshotService.Capture>> captures = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(persistence).save(any(), captures.capture());
        assertThat(captures.getValue()).hasSize(5);
        assertThat(captures.getValue().getLast().error()).isEqualTo("DETAIL_QUOTE_BUDGET_EXHAUSTED");
    }
}
