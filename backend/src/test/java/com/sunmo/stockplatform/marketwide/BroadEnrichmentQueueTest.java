/*
 * BroadEnrichmentQueue(전시장 스캔에서 예산 부족으로 못 받은 시세를 나중에 따로 보강하는 대기열)를 확인해요.
 * 중복 작업·무한 재시도·오래된 작업·평가 고정 시각 이후 가격 저장을 막아야 15:00 이전 정보만으로 평가한다는 원칙이 지켜져요.
 */
package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.marketwide.application.*;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.market.config.*;
import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 시세 보강 대기열의 중복 제거·재시도·만료 규칙을 점검하는 테스트예요. */
class BroadEnrichmentQueueTest {
    // 시세 해석기와 스냅샷 저장은 목(mock, 가짜 객체), 시계는 손으로 옮기는 가변 시계예요.
    private final BroadQuoteResolver quotes = mock(BroadQuoteResolver.class);
    private final BroadSnapshotService snapshots = mock(BroadSnapshotService.class);
    private final MutableClock clock = new MutableClock();
    /**
     * 사용 여부와 용량만 바꾼 대기열을 만드는 도우미예요.
     * 최대 시도 2회, 작업 수명 3분, 재시도 간격 10초이고, 15:00 고정 시각이 있는 종가 추천 설정을 함께 넘기는 것으로 보여요.
     */
    private BroadEnrichmentQueue queue(boolean enabled, int capacity) {
        return new BroadEnrichmentQueue(quotes, snapshots,
                new BroadEnrichmentProperties(enabled, capacity, 2, Duration.ofMinutes(3), Duration.ofSeconds(10)),
                new MarketSessionPolicy(new MarketWideScheduleProperties(false, 120, 30, false,
                        null, null, null, null, null, List.of())),
                new ClosingRecommendationProperties(20, 4, new BigDecimal("55"), LocalTime.of(14, 30),
                        LocalTime.of(15, 0), LocalTime.of(15, 20), Duration.ofSeconds(10)), clock);
    }
    /** 상세 시세 예산이 바닥나 보강이 필요한 후보 기록을 만드는 도우미예요. */
    private BroadSnapshotService.Capture capture(String code) {
        Stock stock = mock(Stock.class);
        when(stock.getStockCode()).thenReturn(code);
        return new BroadSnapshotService.Capture(new BroadCandidate(stock, BigDecimal.TEN, Map.of(), null),
                null, BigDecimal.ZERO, "DETAIL_QUOTE_BUDGET_EXHAUSTED");
    }
    /** 현재 시계 시각으로 관측된 REST 시세 데이터를 만드는 도우미예요. */
    private BroadQuoteData data() {
        return new BroadQuoteData(BigDecimal.TEN, BigDecimal.ZERO, 10L, BigDecimal.valueOf(100),
                null, null, null, null, clock.instant(), "REST");
    }

    /**
     * 같은 종목은 합치고 용량을 넘는 작업은 거부하며, 성공한 보강은 새 관측으로 추가 저장하는지 확인해요.
     * 준비: 용량 1 대기열에 같은 005930 두 번과 000660을 넣고, 시세 해석이 성공하게 해요.
     * 기대: 수락 1, 합침 1, 거부 1이고 두 번 처리해도 해석은 1번, 보강 저장 1번, 성공 1이에요.
     */
    @Test
    void deduplicatesAndBoundsCapacityThenAppendsSuccessfulObservation() {
        // 준비
        var queue = queue(true, 1);
        var capture = capture("005930");
        queue.enqueue(List.of(capture, capture, capture("000660")));
        assertThat(queue.snapshot().accepted()).isEqualTo(1);
        assertThat(queue.snapshot().coalesced()).isEqualTo(1);
        assertThat(queue.snapshot().rejected()).isEqualTo(1);
        var data = data();
        when(quotes.resolve(any(), eq(true))).thenReturn(new BroadQuoteResolver.Resolution(data, null, true));
        // 실행
        queue.processOne();
        queue.processOne();
        // 확인
        verify(quotes, times(1)).resolve(any(), eq(true));
        verify(snapshots).saveEnriched(clock.instant(), capture.candidate(), data);
        assertThat(queue.snapshot().succeeded()).isEqualTo(1);
    }

    /**
     * 실패하면 재시도 간격이 지난 뒤에만 다시 시도하고, 시도 한도에서 멈추는지 확인해요.
     * 준비: 시세 해석이 계속 "KIS unavailable"로 실패하게 하고, 시계를 10초·30초씩 옮기며 처리해요.
     * 기대: 간격 전에는 재시도하지 않고 총 2번만 시도한 뒤 소진(exhausted) 1건이며 저장은 없어요.
     */
    @Test
    void retriesOnlyAfterDelayAndStopsAtAttemptLimit() {
        var queue = queue(true, 10);
        queue.enqueue(List.of(capture("005930")));
        when(quotes.resolve(any(), eq(true))).thenReturn(new BroadQuoteResolver.Resolution(null, "KIS unavailable", true));
        queue.processOne();
        queue.processOne();
        verify(quotes, times(1)).resolve(any(), eq(true));
        clock.now = clock.now.plusSeconds(10);
        queue.processOne();
        clock.now = clock.now.plusSeconds(30);
        queue.processOne();
        verify(quotes, times(2)).resolve(any(), eq(true));
        assertThat(queue.snapshot().exhausted()).isEqualTo(1);
        verifyNoInteractions(snapshots);
    }

    /**
     * 오래된 작업은 만료시키고, 같은 종목이 다시 들어와도 작업 나이를 초기화하지 않는지 확인해요.
     * 준비: 작업을 넣고 120초 뒤 같은 종목을 다시 넣은 다음 61초 더 지나 처리해요(총 181초, 수명 3분 초과).
     * 기대: 만료 1건이고 시세 해석·저장은 호출되지 않아요.
     */
    @Test
    void expiresOldJobsAndDoesNotResetAgeOnRepeatedScans() {
        var queue = queue(true, 10);
        var capture = capture("005930");
        queue.enqueue(List.of(capture));
        clock.now = clock.now.plusSeconds(120);
        queue.enqueue(List.of(capture));
        clock.now = clock.now.plusSeconds(61);
        queue.processOne();
        assertThat(queue.snapshot().expired()).isEqualTo(1);
        verifyNoInteractions(quotes, snapshots);
    }

    /**
     * 요청 도중 고정 시각(15:00)을 넘기면 늦게 받은 가격을 저장하지 않는지 확인해요.
     * 준비: 14:59:59(서울)에 시작해, 시세 해석이 시계를 2초 앞으로 옮기게 해요.
     * 기대: 만료 1건이고 저장은 없어요.
     */
    @Test
    void crossingFreezeDuringRequestDoesNotSaveLatePrice() {
        clock.now = Instant.parse("2026-09-14T05:59:59Z");
        var queue = queue(true, 10);
        queue.enqueue(List.of(capture("005930")));
        when(quotes.resolve(any(), eq(true))).thenAnswer(call -> {
            clock.now = clock.now.plusSeconds(2);
            return new BroadQuoteResolver.Resolution(data(), null, true);
        });
        queue.processOne();
        assertThat(queue.snapshot().expired()).isEqualTo(1);
        verifyNoInteractions(snapshots);
    }

    /**
     * 기능이 꺼져 있거나 장 시간 밖이면 KIS를 부르지 않는지 확인해요.
     * 준비: 꺼진 대기열과, 16:00(서울) 시각의 켜진 대기열에 각각 작업을 넣어요.
     * 기대: 꺼진 쪽은 수락 0, 장 마감 뒤 쪽은 거부 1이고 시세 해석·저장은 없어요.
     */
    @Test
    void disabledAndOutsideSessionDoNotCallKis() {
        var disabled = queue(false, 10);
        disabled.enqueue(List.of(capture("005930")));
        disabled.processOne();
        assertThat(disabled.snapshot().accepted()).isZero();
        clock.now = Instant.parse("2026-09-14T07:00:00Z");
        var afterClose = queue(true, 10);
        afterClose.enqueue(List.of(capture("005930")));
        afterClose.processOne();
        assertThat(afterClose.snapshot().rejected()).isEqualTo(1);
        verifyNoInteractions(quotes, snapshots);
    }

    /**
     * DB 저장 실패가 작업 스레드 밖으로 튀어 나가지 않고 재시도 상태로 남는지 확인해요.
     * 준비: 시세 해석은 성공하고 보강 저장은 "DB unavailable" 예외를 던지게 해요.
     * 기대: RETRY 상태 1건, 마지막 오류 "DB unavailable"이에요.
     */
    @Test
    void databaseFailureIsRetriedWithoutEscapingWorker() {
        var queue = queue(true, 10);
        queue.enqueue(List.of(capture("005930")));
        when(quotes.resolve(any(), eq(true))).thenAnswer(call -> new BroadQuoteResolver.Resolution(data(), null, true));
        when(snapshots.saveEnriched(any(), any(), any())).thenThrow(new IllegalStateException("DB unavailable"));
        queue.processOne();
        assertThat(queue.snapshot().states()).containsEntry("RETRY", 1);
        assertThat(queue.snapshot().lastError()).isEqualTo("DB unavailable");
    }

    /** 테스트에서 현재 시각을 직접 바꿀 수 있는 시계예요(기본 14:30 서울). */
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-14T05:30:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
