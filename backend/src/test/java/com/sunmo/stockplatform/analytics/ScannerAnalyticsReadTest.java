/*
 * ScannerAnalyticsService가 스캐너 성과 통계를 한 번의 집계 조회로 만들고, 위험한 조회 범위는 막는지 확인해요.
 * 검출 건마다 따로 조회하면 DB 부하가 커지므로 중요해요.
 */
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

/** 스캐너 성과 통계 조회 방식을 점검하는 테스트예요. */
class ScannerAnalyticsReadTest {
    /**
     * 집계 행 한 번 조회로 통계를 만들고, 같은 요청은 캐시를 쓰는지 확인해요.
     * 준비: 목(mock, 가짜 객체) 저장소가 완료 상태·수익률 1인 집계 행 하나를 돌려주게 해요.
     * 기대: 총 1건, 종가 승률 100, 평균 수익률 1이고 두 번 호출해도 저장소 조회는 1번뿐이에요.
     */
    @Test
    void aggregatesProjectionInSingleReadWithoutDetectionOrIndividualPerformanceQueries() {
        // 준비
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
        // 실행
        var result = service.analytics(2L, from, to);
        // 확인
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.winRateClose()).isEqualByComparingTo("100");
        assertThat(result.averageReturnClose()).isEqualByComparingTo("1");
        assertThat(service.analytics(2L, from, to)).isEqualTo(result);
        verify(performances, times(1)).findAnalyticsRows(2L, from, to);
        verifyNoInteractions(detections);
        verifyNoMoreInteractions(performances);
    }
    /**
     * 너무 긴 기간이나 거꾸로 된 기간은 조회 전에 거부하는지 확인해요.
     * 준비: 94일 범위와 끝이 시작보다 앞선 범위를 요청해요.
     * 기대: 둘 다 ApplicationException이고 저장소는 전혀 호출되지 않아요.
     */
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
