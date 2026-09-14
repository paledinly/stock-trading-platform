package com.sunmo.stockplatform.analytics;
import com.sunmo.stockplatform.analytics.application.*;
import com.sunmo.stockplatform.analytics.infrastructure.*;
import com.sunmo.stockplatform.analytics.domain.PerformanceStatus;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerDetectionRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScannerAnalyticsReadTest {
    @Test
    void aggregatesProjectionInSingleReadWithoutDetectionOrIndividualPerformanceQueries() {
        var detections = mock(ScannerDetectionRepository.class);
        var performances = mock(DetectionPerformanceRepository.class);
        Instant from = Instant.parse("2026-09-14T00:00:00Z"), to = from.plusSeconds(3600);
        var row = mock(AnalyticsRow.class);
        when(row.getStatus()).thenReturn(PerformanceStatus.COMPLETED);
        when(row.getCalculationVersion()).thenReturn("performance-v2");
        when(row.getReturnClose()).thenReturn(BigDecimal.ONE);
        when(row.getDetectedAt()).thenReturn(from);
        when(row.getType()).thenReturn(ScannerType.VOLUME);
        when(performances.findAnalyticsRows(2L, from, to)).thenReturn(List.of(row));
        var service = new ScannerAnalyticsService(detections, performances, new SummaryReadCache(Duration.ofSeconds(60)));
        var result = service.analytics(2L, from, to);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.winRateClose()).isEqualByComparingTo("100");
        assertThat(result.averageReturnClose()).isEqualByComparingTo("1");
        assertThat(service.analytics(2L, from, to)).isEqualTo(result);
        verify(performances, times(1)).findAnalyticsRows(2L, from, to);
        verifyNoInteractions(detections);
        verifyNoMoreInteractions(performances);
    }
    @Test
    void refusesOversizedOrReversedRangeBeforeQuerying() {
        var detections = mock(ScannerDetectionRepository.class);
        var performances = mock(DetectionPerformanceRepository.class);
        var service = new ScannerAnalyticsService(detections, performances);
        Instant from = Instant.parse("2026-09-14T00:00:00Z");
        assertThatThrownBy(() -> service.analytics(null, from, from.plus(Duration.ofDays(94))))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
        assertThatThrownBy(() -> service.analytics(null, from, from.minusSeconds(1)))
                .isInstanceOf(com.sunmo.stockplatform.common.error.ApplicationException.class);
        verifyNoInteractions(detections, performances);
    }
}
