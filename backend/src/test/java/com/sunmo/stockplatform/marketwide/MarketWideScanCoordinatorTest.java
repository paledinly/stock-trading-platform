package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import com.sunmo.stockplatform.marketwide.application.*;
import com.sunmo.stockplatform.marketwide.domain.MarketWideScanRun;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketWideScanRunRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketWideScanCoordinatorTest {
    private final MarketWideScannerService scanner = mock(MarketWideScannerService.class);
    private final MarketWideScanRunRepository runs = mock(MarketWideScanRunRepository.class);
    private final MarketWideDiagnostics diagnostics = new MarketWideDiagnostics();
    private final MarketWideScanCoordinator coordinator = new MarketWideScanCoordinator(scanner, runs, diagnostics);

    @Test
    void persistsSuccessfulScheduledRunAndSkipsCompletedBucket() {
        Instant bucket = Instant.parse("2026-09-07T05:00:00Z");
        BroadScanResponse response = mock(BroadScanResponse.class);
        when(response.scannedCount()).thenReturn(80);
        when(response.candidateCount()).thenReturn(20);
        when(response.rankingSources()).thenReturn(java.util.List.of());
        when(scanner.scan(null, 120, 30, false)).thenReturn(response);
        when(runs.findByScheduledFor(bucket)).thenReturn(Optional.empty());
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        coordinator.scheduled(bucket, 120, 30, false);

        assertThat(diagnostics.snapshot().completedRuns()).isEqualTo(1);
        assertThat(diagnostics.snapshot().lastScannedCount()).isEqualTo(80);
        verify(runs, times(2)).save(any(MarketWideScanRun.class));

        MarketWideScanRun completed = mock(MarketWideScanRun.class);
        when(completed.getStatus()).thenReturn(MarketWideScanRun.Status.COMPLETED);
        when(runs.findByScheduledFor(bucket)).thenReturn(Optional.of(completed));
        coordinator.scheduled(bucket, 120, 30, false);
        assertThat(diagnostics.snapshot().lastSkipReason()).isEqualTo("BUCKET_ALREADY_COMPLETED");
        verify(scanner, times(1)).scan(null, 120, 30, false);
    }

    @Test
    void recordsFailureAndAllowsTheBucketToBeRetriedLater() {
        Instant bucket = Instant.parse("2026-09-07T05:02:00Z");
        when(runs.findByScheduledFor(bucket)).thenReturn(Optional.empty());
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scanner.scan(null, 120, 30, false)).thenThrow(new IllegalStateException("KIS unavailable"));

        coordinator.scheduled(bucket, 120, 30, false);

        assertThat(diagnostics.snapshot().failedRuns()).isEqualTo(1);
        assertThat(diagnostics.snapshot().lastError()).isEqualTo("KIS unavailable");
        verify(runs, times(2)).save(any(MarketWideScanRun.class));
    }
}
