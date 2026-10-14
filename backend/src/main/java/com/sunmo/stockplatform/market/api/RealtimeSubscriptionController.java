/*
 * 실시간 체결 구독 목록을 사람이 직접 추가·삭제하고 조회하는 API(프로그램끼리 요청·응답하는 창구)예요.
 * 조회할 때는 구독 출처, 종목명, 구독 상태, 정밀 후보 점수까지 한 줄씩 합쳐서 보여 줘요.
 * 웹 화면의 실시간 구독 관리 패널이 /api/v1/market/subscriptions로 이 컨트롤러를 호출해요.
 */
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

/**
 * KIS 실시간 구독 자리를 수동(MANUAL)으로 쓰거나 비우고, 전체 구독 현황을 보여 주는 REST(HTTP 주소로 기능을 제공하는 방식) 컨트롤러예요.
 * 주차장 관리실 창구처럼, 손님이 직접 자리를 잡거나 빼고 지금 누가 어느 자리를 왜 쓰는지 안내판을 보여 줘요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/market/subscriptions")
public class RealtimeSubscriptionController {
    private final RealtimeSubscriptionRegistry registry;
    private final StockService stocks;
    private final StockRepository stockRows;
    private final RealtimeDiagnostics diagnostics;
    private final ObjectProvider<PrecisionSubscriptionAllocator> precision;

    /**
     * 하는 일: 구독 목록, 종목 조회, 진단, 정밀 후보 배정기를 주입받아요(의존성 주입: 스프링이 필요한 객체를 넣어 주는 것).
     * 받는 것: 위 부품들. 정밀 배정기는 기능이 꺼져 있으면 없을 수 있어 ObjectProvider로 받아요.
     */
    public RealtimeSubscriptionController(RealtimeSubscriptionRegistry registry, StockService stocks,
            StockRepository stockRows, RealtimeDiagnostics diagnostics,
            ObjectProvider<PrecisionSubscriptionAllocator> precision) {
        this.registry = registry;
        this.stocks = stocks;
        this.stockRows = stockRows;
        this.diagnostics = diagnostics;
        this.precision = precision;
    }

    /**
     * 하는 일: 종목을 수동(MANUAL) 출처로 실시간 구독에 추가해요.
     * 받는 것: 종목코드(영문 대문자·숫자 6~12자). 없는 종목이면 getByCode에서 예외가 나요.
     * 돌려주는 것: 실제로 바뀌었는지 여부와 구독 한도·남은 자리.
     */
    @PostMapping("/{stockCode}")
    public Response subscribe(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        stocks.getByCode(stockCode);
        return new Response(stockCode, registry.add(stockCode, RealtimeSubscriptionRegistry.Source.MANUAL),
                registry.limit(), registry.remaining());
    }

    /**
     * 하는 일: 종목에서 수동(MANUAL) 출처만 떼어 내요. 다른 출처가 남아 있으면 구독은 계속돼요.
     * 받는 것: 종목코드. 돌려주는 것: 변경 여부와 구독 한도·남은 자리.
     */
    @DeleteMapping("/{stockCode}")
    public Response unsubscribe(@PathVariable @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode) {
        return new Response(stockCode, registry.remove(stockCode, RealtimeSubscriptionRegistry.Source.MANUAL),
                registry.limit(), registry.remaining());
    }

    /**
     * 하는 일: 현재 구독 중인 모든 종목을 이름·시장·출처·상태·정밀 점수와 합쳐 보여 줘요.
     * 받는 것: 없음.
     * 돌려주는 것: 종목코드 순으로 정렬된 항목 목록과 한도, 정밀 배정기 상태·수용량.
     */
    @GetMapping
    public Snapshot list() {
        // 준비: 구독 목록, 종목 정보, 구독 상태, 정밀 배정 정보를 각각 종목코드 기준 Map으로 모아요.
        Map<String, Set<RealtimeSubscriptionRegistry.Source>> entries = registry.entries();
        Map<String, Stock> rows = stockRows.findByStockCodeIn(entries.keySet()).stream()
                .collect(Collectors.toMap(Stock::getStockCode, Function.identity(), (left, right) -> left));
        Map<String, RealtimeDiagnostics.SubscriptionStatus> states = diagnostics.snapshot().subscriptions();
        PrecisionSubscriptionAllocator allocator = precision.getIfAvailable();
        PrecisionSubscriptionAllocator.Snapshot allocation = allocator == null ? null : allocator.snapshot();
        Map<String, PrecisionSubscriptionAllocator.AllocationView> allocations = allocation == null ? Map.of()
                : allocation.allocations().stream().collect(Collectors.toMap(
                        PrecisionSubscriptionAllocator.AllocationView::stockCode, Function.identity(), (left, right) -> left));
        // 처리: 구독 종목마다 위 정보를 합쳐 한 줄(Item)로 만들어요. 없는 정보는 null로 둬요.
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

    /** 추가·삭제 응답이에요. changed는 이번 요청으로 출처 목록이 실제로 바뀌었는지예요. */
    public record Response(String stockCode, boolean changed, int limit, int remaining) {
    }

    /** 구독 목록 조회 응답이에요. 정밀 배정기가 없으면 precisionState·precisionCapacity는 null이에요. */
    public record Snapshot(Set<String> stockCodes,
            java.util.Map<String, Set<RealtimeSubscriptionRegistry.Source>> sources, int limit, int remaining,
            List<Item> items, String precisionState, Integer precisionCapacity) {
    }

    /**
     * stateUpdatedAt is when the subscription state last changed; precisionAddedAt is when the allocator took the slot.
     * 구독 종목 한 줄이에요. stateUpdatedAt은 구독 상태가 마지막으로 바뀐 시각, precisionAddedAt은 정밀 배정기가 자리를 잡은 시각이에요.
     */
    public record Item(String stockCode, String stockName, String market, List<String> sources, String state, String stateMessage,
            Instant stateUpdatedAt, BigDecimal precisionScore, Instant precisionAddedAt) {
    }
}
