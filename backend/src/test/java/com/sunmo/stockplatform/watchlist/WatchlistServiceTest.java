package com.sunmo.stockplatform.watchlist;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.watchlist.application.WatchlistService;
import com.sunmo.stockplatform.watchlist.domain.WatchlistGroup;
import com.sunmo.stockplatform.watchlist.domain.WatchlistItem;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistGroupRepository;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
import com.sunmo.stockplatform.watchlist.config.WatchlistRealtimeProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WatchlistServiceTest {
    private final WatchlistGroupRepository groups = mock(WatchlistGroupRepository.class);
    private final WatchlistItemRepository items = mock(WatchlistItemRepository.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry subscriptions = mock(com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry.class);
    private final WatchlistService service = new WatchlistService(groups, items, stocks, subscriptions,
            new WatchlistRealtimeProperties(1));

    @Test
    void createsTrimmedGroupAtTheEnd() {
        when(groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(1L)).thenReturn(List.of(new WatchlistGroup(1L, "기존", 0)));
        when(groups.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var created = service.createGroup("  성장주  ", null);
        assertThat(created.name()).isEqualTo("성장주");
        assertThat(created.displayOrder()).isEqualTo(1);
    }

    @Test
    void rejectsStaleGroupReorder() {
        var group = new WatchlistGroup(1L, "관심", 0);
        when(groups.findByIdAndOwnerId(7L, 1L)).thenReturn(Optional.of(group));
        assertThatThrownBy(() -> service.updateGroup(7L, null, 2, 1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("refresh and retry");
        verify(groups, never()).save(any());
    }

    @Test
    void savesItemWithoutConsumingRealtimeSubscription() {
        WatchlistGroup group = new WatchlistGroup(1L, "관심", 0);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");
        when(stock.getStockName()).thenReturn("삼성전자");
        when(stock.getMarket()).thenReturn(Market.KOSPI);
        when(groups.findByIdAndOwnerId(7L, 1L)).thenReturn(Optional.of(group));
        when(stocks.findByStockCodeAndActiveTrue("005930")).thenReturn(Optional.of(stock));
        when(items.save(any(WatchlistItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.addItem(7L, "005930", null).stockCode()).isEqualTo("005930");
        verify(items).save(any(WatchlistItem.class));
        verifyNoInteractions(subscriptions);
    }

    @Test
    void pinsSelectedItemForRealtimeWithinDedicatedLimit() {
        WatchlistGroup group = new WatchlistGroup(1L, "관심", 0);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");
        when(stock.getStockName()).thenReturn("삼성전자");
        when(stock.getMarket()).thenReturn(Market.KOSPI);
        WatchlistItem item = new WatchlistItem(group, stock, 0);
        when(items.findByIdAndGroupOwnerId(3L, 1L)).thenReturn(Optional.of(item));

        var updated = service.updateRealtime(3L, true, 0);

        assertThat(updated.realtimePinned()).isTrue();
        verify(subscriptions).add("005930",
                com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry.Source.WATCHLIST);
    }

    @Test
    void rejectsNewRealtimePinWhenDedicatedLimitIsReached() {
        WatchlistGroup group = new WatchlistGroup(1L, "관심", 0);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(2L);
        when(stock.getStockCode()).thenReturn("000660");
        WatchlistItem item = new WatchlistItem(group, stock, 0);
        when(items.findByIdAndGroupOwnerId(4L, 1L)).thenReturn(Optional.of(item));
        when(items.countDistinctRealtimePinnedStocksByOwnerId(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRealtime(4L, true, 0))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("limit reached");
        verifyNoInteractions(subscriptions);
    }
}
