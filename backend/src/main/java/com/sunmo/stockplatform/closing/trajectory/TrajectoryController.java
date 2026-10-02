package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.closing.application.ClosingTradingCalendar.ZONE;

@RestController
@RequestMapping("/api/v1/closing-trajectory")
public class TrajectoryController {
    private final TrajectoryStore store;
    private final TrajectoryService service;
    private final MorningOutcomeService outcomes;
    private final InvestorFlowCollector flow;
    private final MicrostructureStore microStore;
    private final MicrostructureService microService;
    private final OrderbookCollector books;
    public TrajectoryController(TrajectoryStore store, TrajectoryService service, MorningOutcomeService outcomes, InvestorFlowCollector flow,
            MicrostructureStore microStore, MicrostructureService microService, OrderbookCollector books) {
        this.store = store; this.service = service; this.outcomes = outcomes; this.flow = flow;
        this.microStore=microStore;this.microService=microService;this.books=books;
    }
    @GetMapping("/microstructure/status") public Map<String,Object> microStatus() {
        return TrajectoryModel.fields("aggregation",microService.status(),"orderbook",books.status());
    }
    @GetMapping("/microstructure/minutes") public List<MicrostructureModel.Minute> microMinutes(@RequestParam String symbol,@RequestParam LocalDate date) {
        return microStore.minutes(symbol,date.atStartOfDay(ZONE).toInstant(),date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    @GetMapping("/flow/status") public Map<String, Object> flowStatus() { return flow.status(); }
    @GetMapping("/flow") public List<TrajectoryModel.Context> flow(@RequestParam String symbol, @RequestParam LocalDate date) {
        return store.contexts(symbol, symbol, date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant())
                .stream().filter(r -> r.kind().endsWith("_NET_BUY") && r.receivedAt().isBefore(date.plusDays(1).atStartOfDay(ZONE).toInstant())).toList();
    }
    @GetMapping("/status") public Map<String, Object> status() { return service.status(); }
    @GetMapping("/minutes") public List<TrajectoryModel.Minute> minutes(@RequestParam String symbol, @RequestParam LocalDate date) {
        return store.minutes(symbol, date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    @GetMapping("/snapshots") public List<TrajectoryModel.Snapshot> snapshots(@RequestParam LocalDate date) {
        return store.snapshots(date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    @GetMapping("/outcomes") public List<Map<String, Object>> outcomes(@RequestParam LocalDate date) {
        return store.outcomes(date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
    @PostMapping("/outcomes/track") public Map<String, Integer> track(@RequestParam LocalDate date) {
        return Map.of("tracked", outcomes.track(date));
    }
}
