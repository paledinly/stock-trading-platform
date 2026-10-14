/*
 * 관심종목 그룹과 항목의 조회·추가·수정·삭제, 그리고 실시간 고정 켜기/끄기를 처리하는 서비스예요.
 * 실시간 고정을 바꾸면 실시간 구독 레지스트리(WATCHLIST 출처)에도 종목을 올리거나 내려요.
 * WatchlistController가 호출해요. 현재는 사용자 한 명(owner 1)만 있다고 보고 동작해요.
 */
package com.sunmo.stockplatform.watchlist.application;

import com.sunmo.stockplatform.common.error.*;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.watchlist.api.WatchlistResponse;
import com.sunmo.stockplatform.watchlist.domain.*;
import com.sunmo.stockplatform.watchlist.infrastructure.*;
import com.sunmo.stockplatform.watchlist.config.WatchlistRealtimeProperties;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 관심종목 관리 서비스예요.
 * 서랍장 관리인처럼 서랍(그룹)을 만들고 물건(종목)을 넣고 옮기며,
 * "항상 켜 두기" 스티커(실시간 고정)를 붙이면 실시간 시세 구독 자리도 함께 잡아 줘요.
 * 모든 public 메서드는 트랜잭션(여러 작업을 '전부 성공하거나 전부 취소'하게 묶는 것) 안에서 실행돼요.
 */
@Service
@Transactional
public class WatchlistService {
    private static final long OWNER_ID = 1L;
    private final WatchlistGroupRepository groups;
    private final WatchlistItemRepository items;
    private final StockRepository stocks;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final WatchlistRealtimeProperties realtime;

    /**
     * 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 것)으로
     * 그룹/항목/종목 레포지토리(DB 읽기/쓰기 창구), 실시간 구독 레지스트리, 고정 한도 설정을 받아요.
     */
    public WatchlistService(WatchlistGroupRepository groups, WatchlistItemRepository items, StockRepository stocks,
            RealtimeSubscriptionRegistry subscriptions, WatchlistRealtimeProperties realtime) {
        this.groups = groups;
        this.items = items;
        this.stocks = stocks;
        this.subscriptions = subscriptions;
        this.realtime = realtime;
    }

    /**
     * 그룹 목록과 각 그룹의 항목을 돌려줘요.
     * 받는 것: 없음. 돌려주는 것: 그룹 순서대로 정렬된 WatchlistResponse.
     * 항목은 한 번의 조회로 모두 가져온 뒤 그룹별로 나눠서, 그룹마다 DB를 따로 부르지 않게 해요.
     */
    @Transactional(readOnly = true)
    public WatchlistResponse getWatchlists() {
        List<WatchlistGroup> owned = groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID);
        if (owned.isEmpty())
            return new WatchlistResponse(List.of());
        Map<Long, List<WatchlistItem>> byGroup = items
                .findByGroupIdInOrderByGroupIdAscDisplayOrderAscIdAsc(
                        owned.stream().map(WatchlistGroup::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(i -> i.getGroup().getId(), LinkedHashMap::new, Collectors.toList()));
        return new WatchlistResponse(owned.stream()
                .map(g -> WatchlistResponse.Group.from(g, byGroup.getOrDefault(g.getId(), List.of()))).toList());
    }

    /**
     * 새 그룹을 만들어요.
     * 받는 것: 이름(앞뒤 공백 제거), 표시 순서(없으면 맨 뒤). 돌려주는 것: 만든 그룹(항목 없음).
     * 같은 이름이 이미 있으면 409 충돌 오류를 던져요.
     */
    public WatchlistResponse.Group createGroup(String rawName, Integer displayOrder) {
        String name = normalize(rawName);
        if (groups.existsByOwnerIdAndName(OWNER_ID, name))
            duplicate("Watchlist group already exists");
        int order = displayOrder == null ? groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID).size()
                : displayOrder;
        return WatchlistResponse.Group.from(groups.save(new WatchlistGroup(OWNER_ID, name, order)), List.of());
    }

    /**
     * 그룹 이름이나 순서를 바꿔요.
     * 받는 것: 그룹 id, 새 이름(선택), 새 순서(선택), 화면이 알던 version. 돌려주는 것: 바뀐 그룹(항목 목록은 비워서 돌려줘요).
     * version이 다르면 그사이 다른 수정이 있었다는 뜻이라 409로 거절해요.
     */
    public WatchlistResponse.Group updateGroup(long id, String name, Integer displayOrder, long version) {
        WatchlistGroup group = requireGroup(id);
        checkVersion(group.getVersion(), version);
        String value = name == null ? null : normalize(name);
        if (value != null && !value.equals(group.getName()) && groups.existsByOwnerIdAndName(OWNER_ID, value))
            duplicate("Watchlist group already exists");
        group.update(value, displayOrder);
        return WatchlistResponse.Group.from(group, List.of());
    }

    /**
     * 그룹을 지워요. 안의 항목은 DB의 ON DELETE CASCADE로 함께 지워져요.
     * 받는 것: 그룹 id. 돌려주는 것: 없음.
     */
    public void deleteGroup(long id) {
        // 1) 지우기 전에 항목 목록을 따로 챙겨 둬요(구독 정리에 필요해요).
        WatchlistGroup group = requireGroup(id);
        List<WatchlistItem> removed = items.findByGroupIdInOrderByGroupIdAscDisplayOrderAscIdAsc(List.of(id));
        // 2) 삭제를 DB에 바로 반영(flush)해야 아래 개수 세기가 삭제 후 상태를 봐요.
        groups.delete(group);
        groups.flush();
        // 3) 다른 그룹에서도 더는 고정하지 않는 종목만 실시간 구독에서 내려요.
        for (WatchlistItem item : removed) {
            Long stockId = item.getStock().getId();
            if (item.isRealtimePinned() && items.countByStockIdAndRealtimePinnedTrue(stockId) == 0)
                subscriptions.remove(item.getStock().getStockCode(), RealtimeSubscriptionRegistry.Source.WATCHLIST);
        }
    }

    /**
     * 그룹에 종목을 추가해요.
     * 받는 것: 그룹 id, 종목 코드, 순서(없으면 맨 뒤). 돌려주는 것: 추가된 항목.
     * 종목이 없거나 비활성이면 404, 이미 그룹에 있으면 409를 던져요.
     */
    public WatchlistResponse.Item addItem(long groupId, String stockCode, Integer displayOrder) {
        WatchlistGroup group = requireGroup(groupId);
        Stock stock = stocks.findByStockCodeAndActiveTrue(stockCode)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Stock not found: " + stockCode));
        if (items.existsByGroupIdAndStockId(groupId, stock.getId()))
            duplicate("Stock is already in this group");
        int order = displayOrder == null ? Math.toIntExact(items.countByGroupId(groupId)) : displayOrder;
        try {
            return WatchlistResponse.Item.from(items.save(new WatchlistItem(group, stock, order)));
        } catch (DataIntegrityViolationException e) {
            // 위 확인과 저장 사이에 동시에 같은 종목이 들어온 경우, DB 유일 제약 위반을 409 오류로 바꿔요.
            // duplicate()는 항상 예외를 던지므로 아래 return null에는 도달하지 않아요(컴파일러용).
            duplicate("Stock is already in this group");
            return null;
        }
    }

    /**
     * 항목을 다른 그룹이나 다른 순서로 옮겨요.
     * 받는 것: 항목 id, 대상 그룹 id, 새 순서, version. 돌려주는 것: 옮긴 항목.
     * 대상 그룹에 같은 종목이 이미 있으면 409를 던져요.
     */
    public WatchlistResponse.Item moveItem(long id, long groupId, int displayOrder, long version) {
        WatchlistItem item = requireItem(id);
        checkVersion(item.getVersion(), version);
        WatchlistGroup target = requireGroup(groupId);
        if (!item.getGroup().getId().equals(groupId)
                && items.existsByGroupIdAndStockId(groupId, item.getStock().getId()))
            duplicate("Stock is already in the target group");
        item.move(target, displayOrder);
        return WatchlistResponse.Item.from(item);
    }

    /**
     * 항목의 실시간 고정을 켜거나 꺼요.
     * 받는 것: 항목 id, 켤지 여부, version. 돌려주는 것: 바뀐 항목(이미 원하는 상태면 그대로).
     * 켤 때는 고정 한도와 구독 슬롯을 확인하고, 끌 때는 같은 종목을 고정한 다른 항목이 없을 때만 구독을 내려요.
     */
    public WatchlistResponse.Item updateRealtime(long id, boolean enabled, long version) {
        WatchlistItem item = requireItem(id);
        checkVersion(item.getVersion(), version);
        if (item.isRealtimePinned() == enabled)
            return WatchlistResponse.Item.from(item);
        Long stockId = item.getStock().getId();
        String code = item.getStock().getStockCode();
        if (enabled) {
            // 같은 종목이 이미 다른 그룹에서 고정돼 있으면 서로 다른 종목 수가 늘지 않으니 한도 검사를 건너뛰어요.
            boolean sameStockAlreadyPinned = items.countByStockIdAndRealtimePinnedTrue(stockId) > 0;
            if (!sameStockAlreadyPinned
                    && items.countDistinctRealtimePinnedStocksByOwnerId(OWNER_ID) >= realtime.maxPinned())
                throw error(HttpStatus.CONFLICT,
                        "Realtime watchlist limit reached: " + realtime.maxPinned());
            // ⚠️ 확인 필요: 구독 등록이 DB 커밋보다 먼저 일어나요. 커밋이 실패하면(예: version 충돌)
            // DB는 고정이 꺼진 채인데 구독만 남을 수 있어요. 끄는 쪽(remove)도 같은 순서 문제가 있어요.
            try {
                subscriptions.add(code, RealtimeSubscriptionRegistry.Source.WATCHLIST);
            } catch (IllegalStateException error) {
                throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                        "No realtime subscription slot is available");
            }
            item.setRealtimePinned(true);
        } else {
            // 변경을 먼저 DB에 반영(flush)해야 아래 개수에 이 항목이 빠진 결과가 나와요.
            item.setRealtimePinned(false);
            items.flush();
            if (items.countByStockIdAndRealtimePinnedTrue(stockId) == 0)
                subscriptions.remove(code, RealtimeSubscriptionRegistry.Source.WATCHLIST);
        }
        return WatchlistResponse.Item.from(item);
    }

    /**
     * 항목 하나를 지워요. 고정돼 있던 종목이고 더 이상 고정한 항목이 없으면 실시간 구독도 내려요.
     * 받는 것: 항목 id. 돌려주는 것: 없음.
     */
    public void deleteItem(long id) {
        WatchlistItem item = requireItem(id);
        Long stockId = item.getStock().getId();
        String code = item.getStock().getStockCode();
        items.delete(item);
        items.flush();
        if (item.isRealtimePinned() && items.countByStockIdAndRealtimePinnedTrue(stockId) == 0)
            subscriptions.remove(code, RealtimeSubscriptionRegistry.Source.WATCHLIST);
    }

    /** 내 그룹을 id로 찾아요. 받는 것: 그룹 id. 돌려주는 것: 그룹(없으면 404 오류). */
    private WatchlistGroup requireGroup(long id) {
        return groups.findByIdAndOwnerId(id, OWNER_ID)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Watchlist group not found: " + id));
    }

    /** 내 항목을 id로 찾아요. 받는 것: 항목 id. 돌려주는 것: 항목(없으면 404 오류). */
    private WatchlistItem requireItem(long id) {
        return items.findByIdAndGroupOwnerId(id, OWNER_ID)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Watchlist item not found: " + id));
    }

    /** 그룹 이름의 앞뒤 공백을 지워요. 받는 것: 원래 이름. 돌려주는 것: 정리된 이름(비어 있으면 400 오류). */
    private String normalize(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty())
            throw error(HttpStatus.BAD_REQUEST, "Group name must not be blank");
        return value;
    }

    /** 저장된 version과 요청 version이 다르면 409 오류를 던져요("새로고침 후 다시 시도"). */
    private void checkVersion(long actual, long expected) {
        if (actual != expected)
            throw error(HttpStatus.CONFLICT, "The watchlist changed; refresh and retry");
    }

    /** 중복 상황을 409 충돌 오류로 던져요. 돌려주는 값 없이 항상 예외로 끝나요. */
    private void duplicate(String message) {
        throw error(HttpStatus.CONFLICT, message);
    }

    /** 관심종목용 ApplicationException을 만들어요(코드는 INVALID_REQUEST, 상태는 받은 값). 던지지는 않아요. */
    private ApplicationException error(HttpStatus status, String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, status, message);
    }
}
