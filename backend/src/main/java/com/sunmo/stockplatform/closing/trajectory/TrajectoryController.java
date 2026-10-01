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
    public TrajectoryController(TrajectoryStore store, TrajectoryService service, MorningOutcomeService outcomes) {
        this.store = store; this.service = service; this.outcomes = outcomes;
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
