package com.sunmo.stockplatform.watchlist.api;

import com.sunmo.stockplatform.watchlist.domain.WatchlistGroup;
import com.sunmo.stockplatform.watchlist.domain.WatchlistItem;
import java.util.List;

public record WatchlistResponse(List<Group> groups) {
    public record Group(Long id, String name, int displayOrder, long version, List<Item> items) {
        public static Group from(WatchlistGroup group, List<WatchlistItem> items) {
            return new Group(group.getId(), group.getName(), group.getDisplayOrder(), group.getVersion(), items.stream().map(Item::from).toList());
        }
    }
    public record Item(Long id, Long groupId, String stockCode, String stockName, String market, int displayOrder, long version) {
        public static Item from(WatchlistItem item) {
            return new Item(item.getId(), item.getGroup().getId(), item.getStock().getStockCode(), item.getStock().getStockName(), item.getStock().getMarket().name(), item.getDisplayOrder(), item.getVersion());
        }
    }
}
