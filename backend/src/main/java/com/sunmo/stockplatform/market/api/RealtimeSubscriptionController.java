package com.sunmo.stockplatform.market.api;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.stock.application.StockService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
@Validated @RestController @RequestMapping("/api/v1/market/subscriptions")
public class RealtimeSubscriptionController {private final RealtimeSubscriptionRegistry registry;private final StockService stocks;public RealtimeSubscriptionController(RealtimeSubscriptionRegistry registry,StockService stocks){this.registry=registry;this.stocks=stocks;}@PostMapping("/{stockCode}")public Response subscribe(@PathVariable @Pattern(regexp="[A-Z0-9]{6,12}")String stockCode){stocks.getByCode(stockCode);return new Response(stockCode,registry.add(stockCode));}@GetMapping public Set<String> list(){return registry.all();}public record Response(String stockCode,boolean added){}}
