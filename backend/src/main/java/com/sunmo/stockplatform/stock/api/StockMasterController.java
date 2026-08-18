package com.sunmo.stockplatform.stock.api;

import com.sunmo.stockplatform.stock.application.StockMasterSyncService;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/stocks/master")
public class StockMasterController {
    private final StockMasterSyncService service;
    public StockMasterController(StockMasterSyncService service) { this.service = service; }
    @PostMapping("/sync")
    public SyncResponse synchronize() { return new SyncResponse(service.synchronizeAll(), Instant.now()); }
    public record SyncResponse(int synchronizedCount, Instant synchronizedAt) {}
}
