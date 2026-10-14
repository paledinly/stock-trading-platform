/*
 * MarketWideScanCoordinator가 예약된 전시장 스캔 실행을 기록하고, 이미 끝난 구간은 다시 돌리지 않는지 확인해요.
 * 같은 구간을 중복 스캔하면 KIS 호출 예산을 낭비하고 실패한 구간은 재시도할 수 있어야 해요.
 */
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

/** 전시장 스캔 실행 조정 규칙을 점검하는 테스트예요. */
class MarketWideScanCoordinatorTest {
    // 스캐너와 실행 기록 저장소는 목(mock, 가짜 객체), 진단 정보는 실제 객체를 써요.
    private final MarketWideScannerService scanner = mock(MarketWideScannerService.class);
    private final MarketWideScanRunRepository runs = mock(MarketWideScanRunRepository.class);
    private final MarketWideDiagnostics diagnostics = new MarketWideDiagnostics();
    private final MarketWideScanCoordinator coordinator = new MarketWideScanCoordinator(scanner, runs, diagnostics);

    /**
     * 성공한 예약 실행을 저장하고, 완료된 구간은 건너뛰는지 확인해요.
     * 준비: 스캔이 80종목·후보 20개를 돌려주게 하고, 처음엔 실행 기록이 없다가 두 번째엔 COMPLETED 기록이 있게 해요.
     * 기대: 첫 실행은 완료 1건으로 기록(저장 2번), 두 번째는 BUCKET_ALREADY_COMPLETED로 건너뛰어 스캔은 총 1번이에요.
     */
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

        // 실행: 첫 번째 예약 실행
        coordinator.scheduled(bucket, 120, 30, false);

        // 확인
        assertThat(diagnostics.snapshot().completedRuns()).isEqualTo(1);
        assertThat(diagnostics.snapshot().lastScannedCount()).isEqualTo(80);
        verify(runs, times(2)).save(any(MarketWideScanRun.class));

        // 준비: 같은 구간이 이미 완료된 상태로 바꿔요.
        MarketWideScanRun completed = mock(MarketWideScanRun.class);
        when(completed.getStatus()).thenReturn(MarketWideScanRun.Status.COMPLETED);
        when(runs.findByScheduledFor(bucket)).thenReturn(Optional.of(completed));
        coordinator.scheduled(bucket, 120, 30, false);
        assertThat(diagnostics.snapshot().lastSkipReason()).isEqualTo("BUCKET_ALREADY_COMPLETED");
        verify(scanner, times(1)).scan(null, 120, 30, false);
    }

    /**
     * 실패를 기록하고, 그 구간을 나중에 재시도할 수 있게 두는지 확인해요.
     * 준비: 스캔이 "KIS unavailable" 예외를 던지게 해요.
     * 기대: 실패 1건, 마지막 오류 메시지가 남고 실행 기록은 2번 저장돼요.
     */
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
