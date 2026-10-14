/*
 * PrecisionSubscriptionAllocator(점수 높은 전시장 후보에 남는 실시간 구독 슬롯을 나눠 주는 배분기)를 확인해요.
 * 관심종목·수동 구독 같은 보호된 구독은 지키고, 교체할 때도 새 구독 승인 전까지 기존 종목을 유지해야 데이터 공백이 없어요.
 * ⚠️ 확인 필요: 이 파일은 marketwide/ 폴더에 있지만 package는 marketwide.application이에요. 컴파일은 되지만 폴더와 패키지가 달라 찾기 헷갈릴 수 있어요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.PrecisionSubscriptionProperties;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 정밀 구독 슬롯 배분 규칙을 점검하는 테스트예요. */
class PrecisionSubscriptionAllocatorTest {
    /**
     * 보호된 출처는 그대로 두고, 계산된 여유 슬롯만큼만 후보를 채우는지 확인해요.
     * 준비: 한도 5 등록부에 관심종목·수동 구독 1개씩 두고, 정밀 최대 2·예비 1 설정으로 후보 3개를 배분해요.
     * 기대: 여유 2, 활성 2(A00001, A00002), A00003은 제외되고 남은 슬롯은 예비 1개예요.
     */
    @Test
    void preservesProtectedSourcesAndFillsOnlyCalculatedCapacity() {
        // 준비
        RealtimeSubscriptionRegistry registry = registry(5);
        registry.add("005930", RealtimeSubscriptionRegistry.Source.WATCHLIST);
        registry.add("000660", RealtimeSubscriptionRegistry.Source.MANUAL);
        PrecisionSubscriptionAllocator allocator = allocator(registry, Duration.ZERO, "10");

        // 실행
        var result = allocator.reconcile(List.of(candidate("A00001", "90"), candidate("A00002", "80"),
                candidate("A00003", "70")), Instant.parse("2026-09-07T04:00:00Z"));

        // 확인
        assertThat(result.capacity()).isEqualTo(2);
        assertThat(result.activeCount()).isEqualTo(2);
        assertThat(registry.all()).contains("005930", "000660", "A00001", "A00002").doesNotContain("A00003");
        assertThat(registry.remaining()).isEqualTo(1);
    }

    /**
     * 보호 출처와 정밀 후보에 동시에 있는 종목은 슬롯을 두 번 빼지 않는지 확인해요.
     * 준비: 한도 4에 OVERNIGHT 출처로 005930, 000660을 두고, 005930을 포함한 후보 4개를 두 번 배분해요.
     * 기대: 두 번째 여유는 3(000660만 따로 차지), 남은 슬롯 0, 네 종목이 모두 구독돼요.
     */
    @Test
    void aCodeThatIsBothProtectedAndPrecisionIsNotSubtractedTwice() {
        RealtimeSubscriptionRegistry registry = registry(4);
        registry.add("005930", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        registry.add("000660", RealtimeSubscriptionRegistry.Source.OVERNIGHT);
        PrecisionSubscriptionAllocator allocator = new PrecisionSubscriptionAllocator(registry,
                new PrecisionSubscriptionProperties(true, 41, 0, Duration.ZERO, BigDecimal.TEN, LocalTime.of(14, 50)),
                org.mockito.Mockito.mock(PrecisionSubscriptionHistory.class));
        allocator.initialize();
        Instant at = Instant.parse("2026-09-07T04:00:00Z");

        allocator.reconcile(List.of(candidate("005930", "99"), candidate("A00001", "90"), candidate("A00002", "80"),
                candidate("A00003", "70")), at);
        var second = allocator.reconcile(List.of(candidate("005930", "99"), candidate("A00001", "90"),
                candidate("A00002", "80"), candidate("A00003", "70")), at.plusSeconds(1));

        assertThat(second.capacity()).as("only 000660 is held by another source alone").isEqualTo(3);
        assertThat(registry.remaining()).as("every slot is used").isZero();
        assertThat(registry.all()).contains("005930", "000660", "A00001", "A00002");
    }

    /**
     * 교체 대상 새 후보가 구독 승인되기 전까지 기존 후보를 유지하는지 확인해요.
     * 준비: 최소 유지 15분 설정으로 A00001, A00002를 배분하고, 20분 뒤 더 높은 A00003으로 교체를 요청해요.
     * 기대: 승인 전에는 A00002와 A00003이 함께 있고 A00003만 승인 대기이며, 승인 뒤 A00002가 빠지고 남은 슬롯은 1이에요.
     */
    @Test
    void keepsOldCandidateUntilReplacementIsAcknowledged() {
        RealtimeSubscriptionRegistry registry = registry(3);
        PrecisionSubscriptionAllocator allocator = allocator(registry, Duration.ofMinutes(15), "10");
        Instant first = Instant.parse("2026-09-07T04:00:00Z");
        allocator.reconcile(List.of(candidate("A00001", "90"), candidate("A00002", "80")), first);

        var pending = allocator.reconcile(List.of(candidate("A00003", "95"), candidate("A00001", "90")),
                first.plus(Duration.ofMinutes(20)));

        assertThat(pending.allocations()).extracting(PrecisionSubscriptionAllocator.AllocationView::stockCode)
                .contains("A00002", "A00003");
        assertThat(pending.allocations()).filteredOn(PrecisionSubscriptionAllocator.AllocationView::awaitingAcknowledgement)
                .extracting(PrecisionSubscriptionAllocator.AllocationView::stockCode).containsExactly("A00003");
        registry.acknowledge("A00003", true, true, "success");
        assertThat(registry.all()).contains("A00001", "A00003").doesNotContain("A00002");
        assertThat(registry.remaining()).isEqualTo(1);
    }

    /**
     * 새 후보 구독이 거절되면 그 후보만 빼고 기존 후보는 남기는지 확인해요.
     * 준비: A00001, A00002 배분 뒤 A00003으로 교체를 요청하고, A00003 구독이 "limit exceeded"로 거절되게 해요.
     * 기대: A00001, A00002는 남고 A00003은 없어요.
     */
    @Test
    void removesRejectedPrecisionCandidateWithoutDroppingOldCandidate() {
        RealtimeSubscriptionRegistry registry = registry(3);
        PrecisionSubscriptionAllocator allocator = allocator(registry, Duration.ZERO, "10");
        Instant first = Instant.parse("2026-09-07T04:00:00Z");
        allocator.reconcile(List.of(candidate("A00001", "90"), candidate("A00002", "80")), first);
        allocator.reconcile(List.of(candidate("A00003", "95"), candidate("A00001", "90")),
                first.plusSeconds(1));

        registry.acknowledge("A00003", true, false, "limit exceeded");

        assertThat(registry.all()).contains("A00001", "A00002").doesNotContain("A00003");
    }

    /**
     * 교체용 새 구독의 확인 응답이 60초 넘게 오지 않으면 실패로 정리하고, 다음 교체가 다시 가능해지는지 확인해요.
     * 준비: A00001, A00002 배분 뒤 A00003으로 교체를 시작하고 응답을 보내지 않아요.
     * 기대: 60초 이내엔 대기가 유지되고, 61초 뒤엔 A00003이 해지·배정 해제되며 ACK_TIMEOUT 거절 이력이 남고,
     *       A00002는 그대로 남아요. 같은 reconcile에서 더 나은 A00004로 새 교체가 시작돼요.
     */
    @Test
    void expiresReplacementWhoseAcknowledgementNeverArrives() {
        RealtimeSubscriptionRegistry registry = registry(3);
        PrecisionSubscriptionHistory history = org.mockito.Mockito.mock(PrecisionSubscriptionHistory.class);
        PrecisionSubscriptionAllocator allocator = new PrecisionSubscriptionAllocator(registry,
                new PrecisionSubscriptionProperties(true, 2, 1, Duration.ZERO, BigDecimal.TEN, LocalTime.of(14, 50)),
                history);
        allocator.initialize();
        Instant first = Instant.parse("2026-09-07T04:00:00Z");
        allocator.reconcile(List.of(candidate("A00001", "90"), candidate("A00002", "80")), first);
        Instant pendingAt = first.plusSeconds(1);
        allocator.reconcile(List.of(candidate("A00003", "95"), candidate("A00001", "90")), pendingAt);

        // 60초 이내: 대기 유지, 새 교체 없음
        var waiting = allocator.reconcile(List.of(candidate("A00004", "99"), candidate("A00001", "90")),
                pendingAt.plusSeconds(60));
        assertThat(waiting.allocations()).filteredOn(PrecisionSubscriptionAllocator.AllocationView::awaitingAcknowledgement)
                .extracting(PrecisionSubscriptionAllocator.AllocationView::stockCode).containsExactly("A00003");
        assertThat(registry.all()).doesNotContain("A00004");

        // 60초 초과: A00003 정리, A00002 유지, A00004로 새 교체 시작
        Instant expiredAt = pendingAt.plusSeconds(61);
        var expired = allocator.reconcile(List.of(candidate("A00004", "99"), candidate("A00001", "90")), expiredAt);

        assertThat(registry.all()).contains("A00001", "A00002", "A00004").doesNotContain("A00003");
        assertThat(expired.allocations()).extracting(PrecisionSubscriptionAllocator.AllocationView::stockCode)
                .doesNotContain("A00003");
        assertThat(expired.allocations()).filteredOn(PrecisionSubscriptionAllocator.AllocationView::awaitingAcknowledgement)
                .extracting(PrecisionSubscriptionAllocator.AllocationView::stockCode).containsExactly("A00004");
        org.mockito.Mockito.verify(history).rejected("A00003", expiredAt, "ACK_TIMEOUT");
    }

    /**
     * 고정 시각(14:50, 서울) 이후에는 처음 들어온 후보도 새로 추가하지 않는지 확인해요.
     * 준비: 14:50(UTC 05:50)에 후보 하나를 배분해요.
     * 기대: 상태 FROZEN, 활성 0, 구독 없음이에요.
     */
    @Test
    void doesNotAddFirstCandidateAfterFreezeTime() {
        RealtimeSubscriptionRegistry registry = registry(3);
        PrecisionSubscriptionAllocator allocator = allocator(registry, Duration.ZERO, "10");

        var result = allocator.reconcile(List.of(candidate("A00001", "90")),
                Instant.parse("2026-09-07T05:50:00Z"));

        assertThat(result.state()).isEqualTo("FROZEN");
        assertThat(result.activeCount()).isZero();
        assertThat(registry.all()).doesNotContain("A00001");
    }

    /** 정밀 최대 2·예비 1·고정 시각 14:50 설정으로 배분기를 만드는 도우미예요(이력 저장은 목). */
    private PrecisionSubscriptionAllocator allocator(RealtimeSubscriptionRegistry registry, Duration minHold,
            String margin) {
        PrecisionSubscriptionAllocator allocator = new PrecisionSubscriptionAllocator(registry,
                new PrecisionSubscriptionProperties(true, 2, 1, minHold, new BigDecimal(margin),
                        LocalTime.of(14, 50)), org.mockito.Mockito.mock(PrecisionSubscriptionHistory.class));
        allocator.initialize();
        return allocator;
    }

    /** 지정한 한도의 실시간 구독 등록부를 만드는 도우미예요. */
    private RealtimeSubscriptionRegistry registry(int limit) {
        return new RealtimeSubscriptionRegistry(new RealtimeMarketProperties(true, URI.create("ws://localhost"),
                Duration.ZERO, Duration.ofHours(1), 10, limit, Duration.ofMinutes(1)));
    }

    /** 종목코드와 점수로 배분 후보를 만드는 도우미예요. */
    private PrecisionSubscriptionAllocator.Candidate candidate(String code, String score) {
        return new PrecisionSubscriptionAllocator.Candidate(code, new BigDecimal(score));
    }
}
