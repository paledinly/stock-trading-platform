package com.sunmo.stockplatform.analytics;

import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import com.sunmo.stockplatform.analytics.domain.DetectionPerformance;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DetectionPerformanceTrackerStaleTest {
    private final DetectionPerformanceRepository repository = mock(DetectionPerformanceRepository.class);
    private final RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
    private final DetectionPerformanceTracker tracker = new DetectionPerformanceTracker(repository,
            mock(StockCandleRepository.class), mock(QuoteStateStore.class), mock(MarketEventGateway.class),
            diagnostics, mock(PlatformTransactionManager.class));
    // A future session keeps the startup recovery from finalizing the rows.
    private final LocalDate session = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

    @Test
    void oneStaleRowDoesNotDiscardTheBatchAndIsReloaded() {
        DetectionPerformance stale = performance(1L, "005930");
        DetectionPerformance healthy = performance(2L, "005930");
        DetectionPerformance fresh = performance(1L, "005930");
        when(repository.findWithDetectionByStatus(PerformanceStatus.PENDING)).thenReturn(List.of(stale, healthy));
        when(repository.save(stale)).thenThrow(new ObjectOptimisticLockingFailureException(DetectionPerformance.class, 1L));
        when(repository.save(healthy)).thenReturn(healthy);
        when(repository.save(fresh)).thenReturn(fresh);
        when(repository.findWithDetectionById(1L)).thenReturn(Optional.of(fresh));
        tracker.run(null);

        tracker.onTick(tick("005930"));
        tracker.flushDirty();

        verify(repository).save(healthy);
        assertThat(diagnostics.snapshot().lastPerformanceFlushSize()).isEqualTo(1);
        assertThat(diagnostics.snapshot().recentErrors()).singleElement().asString().contains("1 was stale");

        tracker.flushDirty();
        verify(repository).save(fresh);
        verify(repository, times(1)).save(stale);
    }

    @Test
    void staleRowThatIsNoLongerPendingIsDropped() {
        DetectionPerformance stale = performance(1L, "005930");
        DetectionPerformance finalized = performance(1L, "005930");
        when(finalized.getStatus()).thenReturn(PerformanceStatus.COMPLETED);
        when(repository.findWithDetectionByStatus(PerformanceStatus.PENDING)).thenReturn(List.of(stale));
        when(repository.save(stale)).thenThrow(new ObjectOptimisticLockingFailureException(DetectionPerformance.class, 1L));
        when(repository.findWithDetectionById(1L)).thenReturn(Optional.of(finalized));
        tracker.run(null);

        tracker.onTick(tick("005930"));
        tracker.flushDirty();
        tracker.onTick(tick("005930"));
        tracker.flushDirty();

        verify(repository, times(1)).save(any());
    }

    private DetectionPerformance performance(long id, String code) {
        Stock stock = mock(Stock.class);
        when(stock.getStockCode()).thenReturn(code);
        when(stock.getId()).thenReturn(10L);
        ScannerDetection detection = mock(ScannerDetection.class);
        when(detection.getStock()).thenReturn(stock);
        when(detection.getSessionDate()).thenReturn(session);
        when(detection.getDetectedAt()).thenReturn(session.atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
        DetectionPerformance performance = mock(DetectionPerformance.class);
        when(performance.getDetectionId()).thenReturn(id);
        when(performance.getDetection()).thenReturn(detection);
        when(performance.getStatus()).thenReturn(PerformanceStatus.PENDING);
        when(performance.observe(any(), any())).thenReturn(true);
        return performance;
    }

    private MarketTick tick(String code) {
        Instant at = session.atTime(10, 5).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        return new MarketTick(code, session, at, new BigDecimal("10000"), 1, 1, new BigDecimal("10000"), 1);
    }
}
