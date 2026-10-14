/*
 * 시장 전체 스캔이 고른 후보 중 어떤 종목에 정밀 실시간 구독(체결 데이터를 실시간으로 받아 보는 신청) 자리를 줄지 정하는 파일이에요.
 * 실시간 구독 자리는 전체 41개로 제한돼 있어서, 고정/수동/OVERNIGHT 같은 다른 출처가 쓰고 남은 자리만 정밀 구독에 배정해요.
 * 동결 시각(기본 14:50) 이후에는 새로 넣거나 교체하지 않아요. MarketWideScannerService가 스캔마다 reconcile()을 불러요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import com.sunmo.stockplatform.market.config.PrecisionSubscriptionProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 정밀 실시간 구독 자리를 점수 순으로 나눠 주는 배정기예요.
 * 좌석 수가 정해진 식당 매니저처럼, 예약석(다른 출처)을 빼고 남은 자리에 점수 높은 손님을 앉히고,
 * 훨씬 더 나은 손님이 오면 최소 식사 시간이 지난 가장 약한 손님과 바꿔 앉혀요. 마감 직전(동결)엔 좌석을 바꾸지 않아요.
 */
@Component
public class PrecisionSubscriptionAllocator {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final RealtimeSubscriptionRegistry registry;
    private final PrecisionSubscriptionProperties properties;
    private final PrecisionSubscriptionHistory history;
    // 종목코드 → 현재 정밀 배정 정보(점수, 배정 시각, 마지막으로 후보에 보인 시각).
    private final Map<String, Allocation> allocations = new ConcurrentHashMap<>();
    // 새 종목코드 → 교체될 옛 종목코드. 새 구독이 KIS 확인(ack)을 받으면 옛 종목을 빼요.
    private final Map<String, String> pendingReplacements = new ConcurrentHashMap<>();

    /**
     * 하는 일: 실시간 구독 등록부, 정밀 구독 설정, 구독 이력 기록기를 받아 둬요.
     * 받는 것: 위 세 협력 객체.
     */
    public PrecisionSubscriptionAllocator(RealtimeSubscriptionRegistry registry,
            PrecisionSubscriptionProperties properties, PrecisionSubscriptionHistory history) {
        this.registry = registry;
        this.properties = properties;
        this.history = history;
    }

    /**
     * 하는 일: 앱 시작 직후, 구독 등록부에 "구독 확인 응답이 오면 알려 달라"고 등록해요.
     * 받는 것: 없음.
     * 돌려주는 것: 없음.
     */
    @PostConstruct
    public void initialize() {
        registry.onAcknowledged(this::acknowledged);
    }

    /**
     * 하는 일: 현재 시각 기준으로 새 후보 목록에 맞춰 정밀 구독 배정을 조정해요.
     * 받는 것: 후보(종목코드, 점수) 목록.
     * 돌려주는 것: 조정 후 배정 상태 스냅샷.
     */
    public synchronized Snapshot reconcile(List<Candidate> incoming) {
        return reconcile(incoming, Instant.now());
    }

    /**
     * 하는 일: 배정을 바꾸지 않고 현재 상태(비활성/동결/활성)만 보여줘요.
     * 받는 것: 없음.
     * 돌려주는 것: 배정 상태 스냅샷.
     */
    public synchronized Snapshot snapshot() {
        return snapshot(properties.enabled() ? (frozen(Instant.now()) ? "FROZEN" : "ACTIVE") : "DISABLED",
                capacity(), Instant.now());
    }

    /**
     * 하는 일: 후보 목록에 맞춰 배정을 조정하는 본체예요(시각을 받을 수 있어 테스트하기 쉬워요).
     * 순서: 기존 배정 점수 갱신 → 자리 초과분 정리 → (동결 전이면) 빈자리 채우기 → 교체 시도.
     * 받는 것: 후보 목록, 기준 시각.
     * 돌려주는 것: 배정 상태 스냅샷.
     */
    synchronized Snapshot reconcile(List<Candidate> incoming, Instant now) {
        if (!properties.enabled())
            return snapshot("DISABLED", capacity(), now);

        // 준비: 점수 높은 순으로 정렬하고, 같은 종목이 여러 번 오면 가장 높은 점수 하나만 남겨요.
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        incoming.stream().sorted(Comparator.comparing(Candidate::score).reversed())
                .forEach(candidate -> candidates.putIfAbsent(candidate.stockCode(), candidate));
        // 이미 배정된 종목은 이번 점수와 본 시각으로 갱신해요(배정 안 된 종목은 여기서 추가하지 않아요).
        candidates.forEach((code, candidate) -> allocations.compute(code, (ignored, current) ->
                current == null ? null : current.withScore(candidate.score(), now)));

        // 처리: 자리 수가 줄었으면 동결 여부와 상관없이 약한 배정부터 빼요.
        int capacity = capacity();
        trimExcess(capacity, now);
        if (!frozen(now)) {
            fill(candidates.values(), capacity, now);
            replace(candidates.values(), capacity, now);
        }
        return snapshot(frozen(now) ? "FROZEN" : "ACTIVE", capacity, now);
    }

    /**
     * 하는 일: 빈자리가 있는 동안 점수 높은 후보부터 정밀 구독을 추가해요.
     * 받는 것: 점수순 후보, 자리 수, 기준 시각.
     * 돌려주는 것: 없음.
     */
    private void fill(Collection<Candidate> candidates, int capacity, Instant now) {
        for (Candidate candidate : candidates) {
            if (precisionCount() >= capacity)
                return;
            if (hasPrecision(candidate.stockCode()))
                continue;
            add(candidate, now, null);
        }
    }

    /**
     * 하는 일: 자리가 꽉 찼을 때, 가장 약한 배정보다 점수가 replaceMargin 이상 높은 후보가 있으면 한 건만 교체를 시작해요.
     * 이미 진행 중인 교체가 있으면 기다려요.
     * 받는 것: 점수순 후보, 자리 수, 기준 시각.
     * 돌려주는 것: 없음.
     */
    private void replace(Collection<Candidate> candidates, int capacity, Instant now) {
        // ⚠️ 확인 필요: 교체 대기 중인 구독의 확인 응답(ack)이 끝내 오지 않으면 pendingReplacements가 비지 않아,
        // 이후 교체가 계속 막히는 것으로 보여요(대기 항목을 시간 초과로 정리하는 곳이 보이지 않아요).
        if (capacity == 0 || precisionCount() < capacity || frozen(now) || !pendingReplacements.isEmpty())
            return;
        Allocation weakest = removable(now).orElse(null);
        if (weakest == null)
            return;
        Candidate replacement = candidates.stream()
                .filter(candidate -> !hasPrecision(candidate.stockCode()))
                .filter(candidate -> candidate.score().subtract(weakest.score())
                        .compareTo(properties.replaceMargin()) >= 0)
                .findFirst().orElse(null);
        if (replacement == null)
            return;
        add(replacement, now, weakest.stockCode());
    }

    /**
     * 하는 일: 후보 하나를 정밀 구독으로 등록하고 이력·배정 정보를 남겨요.
     * 교체라면, 이미 구독 중이던 종목은 바로 옛 종목을 빼고, 새 구독이면 확인 응답을 기다리도록 대기 목록에 넣어요.
     * 받는 것: 후보, 기준 시각, 교체될 옛 종목코드(단순 추가면 null).
     * 돌려주는 것: 없음. 등록부가 자리 부족 등으로 거절하면(IllegalStateException) 배정만 지우고 조용히 넘어가요.
     */
    private void add(Candidate candidate, Instant now, String replaceCode) {
        boolean alreadySubscribed = registry.all().contains(candidate.stockCode());
        try {
            registry.add(candidate.stockCode(), RealtimeSubscriptionRegistry.Source.PRECISION);
            recordHistory(() -> history.requested(candidate.stockCode(), now, alreadySubscribed));
            allocations.put(candidate.stockCode(), new Allocation(candidate.stockCode(), candidate.score(), now, now));
            if (replaceCode == null)
                return;
            if (alreadySubscribed) {
                remove(replaceCode);
            } else {
                pendingReplacements.put(candidate.stockCode(), replaceCode);
            }
        } catch (IllegalStateException ignored) {
            allocations.remove(candidate.stockCode());
        }
    }

    /**
     * 하는 일: 정밀 구독 수가 자리 수를 넘으면 뺄 수 있는 가장 약한 배정부터 하나씩 빼요.
     * 받는 것: 자리 수, 기준 시각.
     * 돌려주는 것: 없음. 뺄 수 있는 배정이 없으면(최소 유지 시간 전 등) 초과 상태로 멈춰요.
     */
    private void trimExcess(int capacity, Instant now) {
        while (precisionCount() > capacity) {
            Allocation weakest = removable(now).orElse(null);
            if (weakest == null)
                return;
            remove(weakest.stockCode());
        }
    }

    /**
     * 하는 일: 뺄 수 있는 배정 중 가장 약한 것(점수 최저, 같으면 오래된 것)을 찾아요.
     * 교체 진행 중인 종목(새/옛 모두)과 최소 유지 시간(minHold)이 안 된 종목은 제외해요.
     * 받는 것: 기준 시각.
     * 돌려주는 것: 뺄 배정(없으면 빈 Optional).
     */
    private Optional<Allocation> removable(Instant now) {
        return allocations.values().stream()
                .filter(allocation -> !pendingReplacements.containsKey(allocation.stockCode()))
                .filter(allocation -> !pendingReplacements.containsValue(allocation.stockCode()))
                .filter(allocation -> Duration.between(allocation.addedAt(), now).compareTo(properties.minHold()) >= 0)
                .min(Comparator.comparing(Allocation::score).thenComparing(Allocation::addedAt));
    }

    /**
     * 하는 일: KIS가 구독 신청에 응답(ack)했을 때 불려요.
     * 성공이면 활성화를 기록하고, 교체 대기였다면 옛 종목을 빼요. 실패면 거절을 기록하고 새 종목 배정을 거둬요.
     * 받는 것: 구독 확인 응답(종목코드, 구독/해지 여부, 성공 여부, 메시지).
     * 돌려주는 것: 없음.
     */
    private synchronized void acknowledged(RealtimeSubscriptionRegistry.Acknowledgement acknowledgement) {
        if (!acknowledgement.subscribing())
            return;
        String oldCode = pendingReplacements.remove(acknowledgement.stockCode());
        if (acknowledgement.success()) {
            recordHistory(() -> history.activated(acknowledgement.stockCode(), Instant.now()));
            if (oldCode != null)
                remove(oldCode);
            return;
        }
        // 실패: 옛 종목은 그대로 두고(이미 대기 목록에서 빠졌어요) 새 종목만 정리해요.
        recordHistory(() -> history.rejected(acknowledgement.stockCode(), Instant.now(), acknowledgement.message()));
        registry.remove(acknowledgement.stockCode(), RealtimeSubscriptionRegistry.Source.PRECISION);
        allocations.remove(acknowledgement.stockCode());
    }

    /**
     * 하는 일: 종목의 정밀 구독을 해지하고 배정에서 지운 뒤 종료 이력을 남겨요.
     * 받는 것: 종목코드.
     * 돌려주는 것: 없음.
     */
    private void remove(String stockCode) {
        registry.remove(stockCode, RealtimeSubscriptionRegistry.Source.PRECISION);
        allocations.remove(stockCode);
        recordHistory(() -> history.ended(stockCode, Instant.now(), "ALLOCATOR_REMOVED"));
    }

    /**
     * 하는 일: 이력 기록을 실행하되, 실패해도 구독 처리는 계속되게 예외를 삼켜요.
     * 받는 것: 실행할 기록 작업.
     * 돌려주는 것: 없음.
     */
    private void recordHistory(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { /* metrics failure must not interrupt subscriptions */ }
    }

    /**
     * Slots held only by other sources (pinned, manual, next-session observation) are unavailable. A code that is both
     * protected and precision already counts toward precision, so it is not subtracted twice.
     * (풀이: 다른 출처(고정·수동·다음 거래일 관찰)만 쓰는 자리는 정밀 구독에 못 써요.
     * 다른 출처와 정밀을 함께 가진 종목은 이미 정밀 쪽으로 세므로 두 번 빼지 않아요.)
     * 하는 일: 정밀 구독에 쓸 수 있는 자리 수를 계산해요. = min(정밀 최대치, 전체 한도 − 예비 자리 − 다른 출처 전용 자리).
     * 받는 것: 없음.
     * 돌려주는 것: 정밀 구독 가능 자리 수(0 이상).
     */
    private int capacity() {
        long protectedCodes = registry.entries().values().stream()
                .filter(sources -> !sources.contains(RealtimeSubscriptionRegistry.Source.PRECISION))
                .count();
        return Math.min(properties.maxSubscriptions(),
                Math.max(0, registry.limit() - properties.reserve() - Math.toIntExact(protectedCodes)));
    }

    /**
     * 하는 일: 등록부에서 정밀 출처를 가진 종목 수를 세요.
     * 받는 것: 없음.
     * 돌려주는 것: 정밀 구독 종목 수.
     */
    private int precisionCount() {
        return (int) registry.entries().values().stream()
                .filter(sources -> sources.contains(RealtimeSubscriptionRegistry.Source.PRECISION)).count();
    }

    /**
     * 하는 일: 종목이 이미 정밀 구독 중인지 확인해요.
     * 받는 것: 종목코드.
     * 돌려주는 것: 정밀 구독 중이면 true.
     */
    private boolean hasPrecision(String code) {
        return registry.entries().getOrDefault(code, Set.of())
                .contains(RealtimeSubscriptionRegistry.Source.PRECISION);
    }

    /**
     * 하는 일: 지금이 동결 시각(freezeAt, 기본 14:50) 이후인지 확인해요. 동결 뒤엔 배정을 늘리거나 바꾸지 않아요.
     * 받는 것: 기준 시각.
     * 돌려주는 것: 동결이면 true.
     */
    private boolean frozen(Instant now) {
        return !now.atZone(MARKET_ZONE).toLocalTime().isBefore(properties.freezeAt());
    }

    /**
     * 하는 일: 현재 배정을 점수 높은 순으로 정리해 상태 스냅샷(그 순간의 상태 사본)을 만들어요.
     * 받는 것: 상태 문자열, 자리 수, 기준 시각.
     * 돌려주는 것: 배정 상태 스냅샷.
     */
    private Snapshot snapshot(String state, int capacity, Instant now) {
        List<AllocationView> active = allocations.values().stream()
                .sorted(Comparator.comparing(Allocation::score).reversed())
                .map(value -> new AllocationView(value.stockCode(), value.score(), value.addedAt(), value.lastSeenAt(),
                        pendingReplacements.containsKey(value.stockCode())))
                .toList();
        return new Snapshot(state, now, capacity, active.size(), registry.remaining(), properties.reserve(), active);
    }

    /** 배정 후보 하나예요(종목코드와 점수). */
    public record Candidate(String stockCode, BigDecimal score) {
    }

    /**
     * 배정 상태 묶음이에요. 상태(DISABLED/ACTIVE/FROZEN), 계산 시각, 자리 수, 활성 수, 남은 전체 자리, 예비 자리, 배정 목록을 담아요.
     */
    public record Snapshot(String state, Instant evaluatedAt, int capacity, int activeCount, int remainingSlots,
            int reservedSlots, List<AllocationView> allocations) {
    }

    /**
     * 화면에 보여 줄 배정 한 건이에요. awaitingAcknowledgement는 교체용 새 구독이 KIS 확인을 기다리는 중인지예요.
     */
    public record AllocationView(String stockCode, BigDecimal score, Instant addedAt, Instant lastSeenAt,
            boolean awaitingAcknowledgement) {
    }

    /**
     * 내부 배정 기록이에요. withScore는 배정 시각은 그대로 두고 점수와 마지막 확인 시각만 바꾼 새 기록을 돌려줘요.
     */
    private record Allocation(String stockCode, BigDecimal score, Instant addedAt, Instant lastSeenAt) {
        private Allocation withScore(BigDecimal score, Instant seenAt) {
            return new Allocation(stockCode, score, addedAt, seenAt);
        }
    }
}
