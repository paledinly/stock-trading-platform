package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import com.sunmo.stockplatform.marketwide.domain.MarketWideScanRun;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketWideScanRunRepository;
import com.sunmo.stockplatform.stock.domain.Market;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MarketWideScanCoordinator {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final MarketWideScannerService scanner;
    private final MarketWideScanRunRepository runs;
    private final MarketWideDiagnostics diagnostics;
    private final AtomicBoolean running = new AtomicBoolean();

    public MarketWideScanCoordinator(MarketWideScannerService scanner, MarketWideScanRunRepository runs,
            MarketWideDiagnostics diagnostics) {
        this.scanner = scanner;
        this.runs = runs;
        this.diagnostics = diagnostics;
    }

    public BroadScanResponse manual(Market market, int limit, int candidates, boolean includeEtf) {
        if (!running.compareAndSet(false, true))
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                    "Market-wide scan is already running");
        Instant started = Instant.now();
        diagnostics.started(started, null);
        try {
            BroadScanResponse response = scanner.scan(market, limit, candidates, includeEtf);
            diagnostics.completed(Instant.now(), response);
            return response;
        } catch (RuntimeException error) {
            diagnostics.failed(Instant.now(), rootMessage(error));
            throw error;
        } finally {
            running.set(false);
        }
    }

    public void scheduled(Instant bucket, int limit, int candidates, boolean includeEtf) {
        MarketWideScanRun run = runs.findByScheduledFor(bucket).orElse(null);
        if (run != null && run.getStatus() == MarketWideScanRun.Status.COMPLETED) {
            diagnostics.skipped("BUCKET_ALREADY_COMPLETED", bucket);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            diagnostics.skipped("SCAN_ALREADY_RUNNING", bucket);
            return;
        }
        Instant started = Instant.now();
        if (run == null)
            run = new MarketWideScanRun(bucket.atZone(MARKET_ZONE).toLocalDate(), bucket, started);
        else
            run.restart(started);
        run = runs.save(run);
        diagnostics.started(started, bucket);
        try {
            BroadScanResponse response = scanner.scan(null, limit, candidates, includeEtf);
            Instant completed = Instant.now();
            run.complete(completed, response.scannedCount(), response.candidateCount(), response.fallback());
            runs.save(run);
            diagnostics.completed(completed, response);
        } catch (RuntimeException error) {
            Instant failed = Instant.now();
            run.fail(failed, rootMessage(error));
            runs.save(run);
            diagnostics.failed(failed, rootMessage(error));
        } finally {
            running.set(false);
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
