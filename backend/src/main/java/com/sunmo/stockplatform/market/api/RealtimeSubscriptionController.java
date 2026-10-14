package com.sunmo.stockplatform.market.api;

import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.marketwide.application.PrecisionSubscriptionAllocator;
import com.sunmo.stockplatform.stock.application.StockService;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Validated
@RestController
@RequestMapping("/api/v1/market/subscriptions")
public class RealtimeSubscriptionController {
    private final RealtimeSubscriptionRegistry registry;
    private final StockService stocks;
    private final StockRepository stockRows;
    private final RealtimeDiagnostics diagnostics;
    private final ObjectProvider<PrecisionSubscriptionAllocator> precision;

    public RealtimeSubscriptionController(RealtimeSubscriptionRegistry registry, StockService stocks,
            StockRepository stockRows, RealtimeDiagnostics diagnostics,
            ObjectProvider<PrecisionSubscriptionAllocator> precision) {
        this.registry = registry;
        this.stocks = stocks;
        this.stockRows = stockRows;
        this.diagnostics = diagnostics;
        this.precision = precision;
    }

    @PostMapping("/{stockCode}")
    public Response subscribe(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        stocks.getByCode(stockCode);
        return new Response(stockCode, registry.add(stockCode, RealtimeSubscriptionRegistry.Source.MANUAL),
                registry.limit(), registry.remaining());
    }

    @DeleteMapping("/{stockCode}")
    public Response unsubscribe(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        return new Response(stockCode, registry.remove(stockCode, RealtimeSubscriptionRegistry.Source.MANUAL),
                registry.limit(), registry.remaining());
    }

    @GetMapping
    public Snapshot list() {
        Map<String, Set<RealtimeSubscriptionRegistry.Source>> entries = registry.entries();
        Map<String, Stock> rows = stockRows.findByStockCodeIn(entries.keySet()).stream()
                .collect(Collectors.toMap(Stock::getStockCode, Function.identity(), (left, right) -> left));
        Map<String, RealtimeDiagnostics.SubscriptionStatus> states = diagnostics.snapshot().subscriptions();
        PrecisionSubscriptionAllocator allocator = precision.getIfAvailable();
        PrecisionSubscriptionAllocator.Snapshot allocation = allocator == null ? null : allocator.snapshot();
        Map<String, PrecisionSubscriptionAllocator.AllocationView> allocations = allocation == null ? Map.of()
                : allocation.allocations().stream().collect(Collectors.toMap(
                        PrecisionSubscriptionAllocator.AllocationView::stockCode, Function.identity(), (left, right) -> left));
        List<Item> items = entries.entrySet().stream().map(entry -> {
            String code = entry.getKey();
            RealtimeDiagnostics.SubscriptionStatus state = states.get(code);
            PrecisionSubscriptionAllocator.AllocationView view = allocations.get(code);
            Stock stock = rows.get(code);
            return new Item(code, stock == null ? null : stock.getStockName(),
                    stock == null || stock.getMarket() == null ? null : stock.getMarket().name(), entry.getValue().stream().map(Enum::name).sorted().toList(),
                    state == null ? null : state.state().name(), state == null ? null : state.message(),
                    state == null ? null : state.updatedAt(),
                    view == null ? null : view.score(), view == null ? null : view.addedAt());
        }).sorted(Comparator.comparing(Item::stockCode)).toList();
        return new Snapshot(registry.all(), entries, registry.limit(), registry.remaining(), items,
                allocation == null ? null : allocation.state(), allocation == null ? null : allocation.capacity());
    }

    public record Response(String stockCode, boolean changed, int limit, int remaining) {
    }

    public record Snapshot(Set<String> stockCodes,
            java.util.Map<String, Set<RealtimeSubscriptionRegistry.Source>> sources, int limit, int remaining,
            List<Item> items, String precisionState, Integer precisionCapacity) {
    }

    /** stateUpdatedAt is when the subscription state last changed; precisionAddedAt is when the allocator took the slot. */
    public record Item(String stockCode, String stockName, String market, List<String> sources, String state, String stateMessage,
            Instant stateUpdatedAt, BigDecimal precisionScore, Instant precisionAddedAt) {
    }
}
