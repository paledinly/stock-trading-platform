package com.sunmo.stockplatform.watchlist.application;

import com.sunmo.stockplatform.common.error.*;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.watchlist.api.WatchlistResponse;
import com.sunmo.stockplatform.watchlist.domain.*;
import com.sunmo.stockplatform.watchlist.infrastructure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class WatchlistService {
    private static final long OWNER_ID = 1L;
    private final WatchlistGroupRepository groups;
    private final WatchlistItemRepository items;
    private final StockRepository stocks;
    public WatchlistService(WatchlistGroupRepository groups, WatchlistItemRepository items, StockRepository stocks) { this.groups = groups; this.items = items; this.stocks = stocks; }

    @Transactional(readOnly = true)
    public WatchlistResponse getWatchlists() {
        List<WatchlistGroup> owned = groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID);
        if (owned.isEmpty()) return new WatchlistResponse(List.of());
        Map<Long, List<WatchlistItem>> byGroup = items.findByGroupIdInOrderByGroupIdAscDisplayOrderAscIdAsc(owned.stream().map(WatchlistGroup::getId).toList()).stream()
                .collect(Collectors.groupingBy(i -> i.getGroup().getId(), LinkedHashMap::new, Collectors.toList()));
        return new WatchlistResponse(owned.stream().map(g -> WatchlistResponse.Group.from(g, byGroup.getOrDefault(g.getId(), List.of()))).toList());
    }
    public WatchlistResponse.Group createGroup(String rawName, Integer displayOrder) {
        String name = normalize(rawName);
        if (groups.existsByOwnerIdAndName(OWNER_ID, name)) duplicate("Watchlist group already exists");
        int order = displayOrder == null ? groups.findByOwnerIdOrderByDisplayOrderAscIdAsc(OWNER_ID).size() : displayOrder;
        return WatchlistResponse.Group.from(groups.save(new WatchlistGroup(OWNER_ID, name, order)), List.of());
    }
    public WatchlistResponse.Group updateGroup(long id, String name, Integer displayOrder, long version) {
        WatchlistGroup group = requireGroup(id); checkVersion(group.getVersion(), version);
        String value = name == null ? null : normalize(name);
        if (value != null && !value.equals(group.getName()) && groups.existsByOwnerIdAndName(OWNER_ID, value)) duplicate("Watchlist group already exists");
        group.update(value, displayOrder); return WatchlistResponse.Group.from(group, List.of());
    }
    public void deleteGroup(long id) { groups.delete(requireGroup(id)); }
    public WatchlistResponse.Item addItem(long groupId, String stockCode, Integer displayOrder) {
        WatchlistGroup group = requireGroup(groupId);
        Stock stock = stocks.findByStockCodeAndActiveTrue(stockCode).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Stock not found: " + stockCode));
        if (items.existsByGroupIdAndStockId(groupId, stock.getId())) duplicate("Stock is already in this group");
        int order = displayOrder == null ? Math.toIntExact(items.countByGroupId(groupId)) : displayOrder;
        try { return WatchlistResponse.Item.from(items.save(new WatchlistItem(group, stock, order))); }
        catch (DataIntegrityViolationException e) { duplicate("Stock is already in this group"); return null; }
    }
    public WatchlistResponse.Item moveItem(long id, long groupId, int displayOrder, long version) {
        WatchlistItem item = requireItem(id); checkVersion(item.getVersion(), version); WatchlistGroup target = requireGroup(groupId);
        if (!item.getGroup().getId().equals(groupId) && items.existsByGroupIdAndStockId(groupId, item.getStock().getId())) duplicate("Stock is already in the target group");
        item.move(target, displayOrder); return WatchlistResponse.Item.from(item);
    }
    public void deleteItem(long id) { items.delete(requireItem(id)); }
    private WatchlistGroup requireGroup(long id) { return groups.findByIdAndOwnerId(id, OWNER_ID).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Watchlist group not found: " + id)); }
    private WatchlistItem requireItem(long id) { return items.findByIdAndGroupOwnerId(id, OWNER_ID).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Watchlist item not found: " + id)); }
    private String normalize(String name) { String value = name == null ? "" : name.trim(); if (value.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Group name must not be blank"); return value; }
    private void checkVersion(long actual, long expected) { if (actual != expected) throw error(HttpStatus.CONFLICT, "The watchlist changed; refresh and retry"); }
    private void duplicate(String message) { throw error(HttpStatus.CONFLICT, message); }
    private ApplicationException error(HttpStatus status, String message) { return new ApplicationException(ErrorCode.INVALID_REQUEST, status, message); }
}
