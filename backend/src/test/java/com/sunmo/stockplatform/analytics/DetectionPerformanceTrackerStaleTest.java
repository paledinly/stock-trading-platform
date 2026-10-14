/*
 * DetectionPerformanceTracker(검출 성과를 틱마다 갱신해 모아서 저장하는 추적기)가
 * 낙관적 잠금 충돌(다른 곳에서 먼저 고친 오래된 행, stale) 하나 때문에 나머지 저장을 버리지 않는지 확인해요.
 * 한 행 충돌로 묶음 전체 성과가 사라지면 성과 통계에 구멍이 생기므로 중요해요.
 */
package com.sunmo.stockplatform.analytics;

import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import com.sunmo.stockplatform.analytics.domain.DetectionPerformance;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.analytics.infrastructure.DetectionPerformanceRepository;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
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

/** 성과 추적기의 stale 행 처리 규칙을 점검하는 테스트예요. */
class DetectionPerformanceTrackerStaleTest {
    // 저장소와 주변 의존성은 목(mock, 가짜 객체)이고, 진단 정보만 실제 객체예요.
    private final DetectionPerformanceRepository repository = mock(DetectionPerformanceRepository.class);
    private final RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();
    private final DetectionPerformanceTracker tracker = new DetectionPerformanceTracker(repository,
            mock(StockCandleRepository.class), mock(QuoteStateStore.class), mock(MarketEventGateway.class),
            diagnostics, mock(PlatformTransactionManager.class),
            new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null, null,
                    null, null, List.of())));
    // A future session keeps the startup recovery from finalizing the rows.
    private final LocalDate session = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

    /**
     * stale 행 하나가 묶음 저장을 망치지 않고, 그 행은 다시 읽어 다음에 저장하는지 확인해요.
     * 준비: 대기 중 성과 2건 중 1번 저장 시 낙관적 잠금 예외가 나게 하고, 1번을 다시 읽으면 새 객체(fresh)를 주게 해요.
     * 기대: 첫 저장에서 정상 행만 저장(1건)되고 "1 was stale" 오류가 남으며, 다음 저장에서 fresh가 저장돼요.
     */
    @Test
    void oneStaleRowDoesNotDiscardTheBatchAndIsReloaded() {
        // 준비
        DetectionPerformance stale = performance(1L, "005930");
        DetectionPerformance healthy = performance(2L, "005930");
        DetectionPerformance fresh = performance(1L, "005930");
        when(repository.findWithDetectionByStatus(PerformanceStatus.PENDING)).thenReturn(List.of(stale, healthy));
        when(repository.save(stale)).thenThrow(new ObjectOptimisticLockingFailureException(DetectionPerformance.class, 1L));
        when(repository.save(healthy)).thenReturn(healthy);
        when(repository.save(fresh)).thenReturn(fresh);
        when(repository.findWithDetectionById(1L)).thenReturn(Optional.of(fresh));
        tracker.run(null);

        // 실행
        tracker.onTick(tick("005930"));
        tracker.flushDirty();

        // 확인
        verify(repository).save(healthy);
        assertThat(diagnostics.snapshot().lastPerformanceFlushSize()).isEqualTo(1);
        assertThat(diagnostics.snapshot().recentErrors()).singleElement().asString().contains("1 was stale");

        // 다음 저장 주기에는 다시 읽은 행이 저장돼요.
        tracker.flushDirty();
        verify(repository).save(fresh);
        verify(repository, times(1)).save(stale);
    }

    /**
     * 다시 읽었더니 이미 대기 상태가 아닌 stale 행은 버리는지 확인해요.
     * 준비: 1번 저장이 충돌하고, 다시 읽은 1번은 COMPLETED 상태예요.
     * 기대: 틱과 저장을 두 번 반복해도 저장 시도는 처음 1번뿐이에요.
     */
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

    /** 내일 세션 10:00에 검출된, 대기 상태 성과 목 객체를 만드는 도우미예요. */
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

    /** 세션 10:05의 테스트 틱을 만드는 도우미예요. */
    private MarketTick tick(String code) {
        Instant at = session.atTime(10, 5).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        return new MarketTick(code, session, at, new BigDecimal("10000"), 1, 1, new BigDecimal("10000"), 1);
    }
}
