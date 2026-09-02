package com.sunmo.stockplatform.watchlist;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.watchlist.application.WatchlistService;
import com.sunmo.stockplatform.watchlist.domain.WatchlistGroup;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistGroupRepository;
import com.sunmo.stockplatform.watchlist.infrastructure.WatchlistItemRepository;
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
    private final WatchlistService service = new WatchlistService(groups, items, stocks, subscriptions);

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
}
