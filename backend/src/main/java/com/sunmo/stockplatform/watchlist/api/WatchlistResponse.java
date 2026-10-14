/*
 * 관심종목 API가 돌려주는 응답 형식이에요.
 * DB 엔티티(WatchlistGroup, WatchlistItem)를 화면에 필요한 값만 담은 단순한 형태로 바꿔요.
 * WatchlistController와 WatchlistService가 사용해요.
 */
package com.sunmo.stockplatform.watchlist.api;

import com.sunmo.stockplatform.watchlist.domain.WatchlistGroup;
import com.sunmo.stockplatform.watchlist.domain.WatchlistItem;
import java.util.List;

/**
 * 관심종목 전체 응답이에요. 그룹 목록을 담고, 각 그룹은 자기 항목 목록을 담아요.
 * 서랍장(전체) 안에 서랍(그룹)이 있고, 서랍 안에 물건(항목)이 들어 있는 구조와 같아요.
 */
public record WatchlistResponse(List<Group> groups) {
    /** 그룹 하나의 응답 형식이에요. id, 이름, 순서, version, 항목 목록을 담아요. */
    public record Group(Long id, String name, int displayOrder, long version, List<Item> items) {
        /**
         * 그룹 엔티티와 항목 엔티티 목록을 응답 형식으로 바꿔요.
         * 받는 것: 그룹, 그 그룹의 항목들. 돌려주는 것: Group 응답.
         */
        public static Group from(WatchlistGroup group, List<WatchlistItem> items) {
            return new Group(group.getId(), group.getName(), group.getDisplayOrder(), group.getVersion(),
                    items.stream().map(Item::from).toList());
        }
    }

    /** 항목 하나의 응답 형식이에요. 종목 코드·이름·시장, 순서, 실시간 고정 여부, version을 담아요. */
    public record Item(Long id, Long groupId, String stockCode, String stockName, String market, int displayOrder,
            boolean realtimePinned, long version) {
        /**
         * 항목 엔티티를 응답 형식으로 바꿔요.
         * 받는 것: 항목 엔티티. 돌려주는 것: Item 응답. 그룹과 종목은 지연 로딩이라 트랜잭션 안에서 불러야 하는 것으로 보여요.
         */
        public static Item from(WatchlistItem item) {
            return new Item(item.getId(), item.getGroup().getId(), item.getStock().getStockCode(),
                    item.getStock().getStockName(), item.getStock().getMarket().name(), item.getDisplayOrder(),
                    item.isRealtimePinned(), item.getVersion());
        }
    }
}
