/*
 * WatchlistService(관심종목 그룹·항목 관리)가 그룹 생성·순서 변경·항목 추가·실시간 고정 규칙을 지키는지 확인해요.
 * 관심종목에 추가만 해도 실시간 구독 슬롯을 쓰면 한도가 금방 차기 때문에, 고정한 항목만 구독해야 해요.
 */
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

/** 관심종목 서비스 규칙을 점검하는 테스트예요. */
class WatchlistServiceTest {
    // 저장소와 구독 등록부는 목(mock, 가짜 객체)이고, 실시간 고정 한도는 1개로 둬요.
    private final WatchlistGroupRepository groups = mock(WatchlistGroupRepository.class);
    private final WatchlistItemRepository items = mock(WatchlistItemRepository.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry subscriptions = mock(com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry.class);
    private final WatchlistService service = new WatchlistService(groups, items, stocks, subscriptions,
            new WatchlistRealtimeProperties(1));

    /**
     * 그룹 이름의 앞뒤 공백을 지우고 맨 뒤 순서로 만드는지 확인해요.
     * 준비: 기존 그룹 1개가 있고 "  성장주  "로 새 그룹을 만들어요.
     * 기대: 이름 "성장주", 표시 순서 1이에요.
     */
    @Test
    void createsTrimmedGroupAtTheEnd() {
        when(groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(1L)).thenReturn(List.of(new WatchlistGroup(1L, "기존", 0)));
        when(groups.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var created = service.createGroup("  성장주  ", null);
        assertThat(created.name()).isEqualTo("성장주");
        assertThat(created.displayOrder()).isEqualTo(1);
    }

    /**
     * 오래된 버전(version) 기준의 순서 변경 요청은 거부하는지 확인해요(낙관적 잠금처럼 동시 수정 충돌을 막아요).
     * 준비: 새로 만든(버전 0으로 보이는) 그룹에 버전 1을 들고 순서 2로 바꾸는 요청을 보내요.
     * 기대: "refresh and retry" 예외가 나고 저장하지 않아요.
     */
    @Test
    void rejectsStaleGroupReorder() {
        var group = new WatchlistGroup(1L, "관심", 0);
        when(groups.findByIdAndOwnerId(7L, 1L)).thenReturn(Optional.of(group));
        assertThatThrownBy(() -> service.updateGroup(7L, null, 2, 1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("refresh and retry");
        verify(groups, never()).save(any());
    }

    /**
     * 관심종목 항목을 추가해도 실시간 구독은 쓰지 않는지 확인해요.
     * 준비: 그룹과 활성 종목 005930을 준비하고 항목을 추가해요.
     * 기대: 항목은 저장되고 구독 등록부는 전혀 호출되지 않아요.
     */
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

    /**
     * 실시간 고정을 켜면 전용 한도 안에서 구독을 추가하는지 확인해요.
     * 준비: 고정되지 않은 005930 항목을 실시간 고정으로 바꿔요.
     * 기대: 고정 표시가 켜지고 WATCHLIST 출처로 구독이 추가돼요.
     */
    @Test
    void pinsSelectedItemForRealtimeWithinDedicatedLimit() {
        // 준비
        WatchlistGroup group = new WatchlistGroup(1L, "관심", 0);
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");
        when(stock.getStockName()).thenReturn("삼성전자");
        when(stock.getMarket()).thenReturn(Market.KOSPI);
        WatchlistItem item = new WatchlistItem(group, stock, 0);
        when(items.findByIdAndGroupOwnerId(3L, 1L)).thenReturn(Optional.of(item));

        // 실행
        var updated = service.updateRealtime(3L, true, 0);

        // 확인
        assertThat(updated.realtimePinned()).isTrue();
        verify(subscriptions).add("005930",
                com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry.Source.WATCHLIST);
    }

    /**
     * 전용 한도가 찼으면 새 실시간 고정을 거부하는지 확인해요.
     * 준비: 이미 고정된 종목 수가 1(한도)인 상태에서 000660 항목을 고정하려 해요.
     * 기대: "limit reached" 예외가 나고 구독 등록부는 호출되지 않아요.
     */
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
