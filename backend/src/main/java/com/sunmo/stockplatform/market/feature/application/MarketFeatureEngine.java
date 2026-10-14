/*
 * 모든 실시간 종목의 Feature(판단용 지표) 계산을 관리하는 엔진이에요.
 * 종목마다 IntradayFeatureState를 두고 틱을 넘기며, 직전 5분 칸의 마지막 스냅샷도 따로 보관해 특정 봉 시점의 값을 찾을 수 있게 해요.
 * MarketDataService가 틱마다 onTick을 부르고, Scanner·장마감 추천 등이 latest/at으로 값을 읽어요.
 */
package com.sunmo.stockplatform.market.feature.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목별 지표 수첩(IntradayFeatureState)을 모아 둔 서랍장이에요.
 * 틱이 오면 해당 종목 수첩에 적고, 5분 칸이 바뀔 때는 바로 전 칸의 마지막 페이지를 따로 꽂아 둬요.
 * 그래서 방금 확정된 봉을 평가할 때 "그 봉 시간대의 지표"를 꺼낼 수 있어요.
 */
@Component
public class MarketFeatureEngine {
    private final Map<String, IntradayFeatureState> states = new ConcurrentHashMap<>();
    private final Map<String, MarketFeatureSnapshot> previous = new ConcurrentHashMap<>();
    private final RealtimeDiagnostics diagnostics;

    /** 하는 일: 진단 기록부를 주입받아요. 받는 것: RealtimeDiagnostics. */
    public MarketFeatureEngine(RealtimeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * 하는 일: 틱을 종목 수첩에 반영하고, 5분 칸이 바뀌었으면 바뀌기 직전 스냅샷을 previous에 보관해요.
     * 받는 것: 틱. 돌려주는 것: 이 틱 반영 후 최신 스냅샷(중복·과거 틱이면 이전 값).
     */
    public MarketFeatureSnapshot onTick(MarketTick tick) {
        IntradayFeatureState state = states.computeIfAbsent(tick.stockCode(), IntradayFeatureState::new);
        MarketFeatureSnapshot before = state.latest();
        MarketFeatureSnapshot snapshot = state.accept(tick);
        if (before != null && snapshot != null && !bucket(before.occurredAt()).equals(bucket(snapshot.occurredAt())))
            previous.put(tick.stockCode(), before);
        diagnostics.featureSnapshot(states.size());
        return snapshot;
    }

    /**
     * 하는 일: 종목의 가장 최근 스냅샷을 돌려줘요.
     * 받는 것: 종목코드. 돌려주는 것: 스냅샷, 없으면 빈 Optional.
     */
    public Optional<MarketFeatureSnapshot> latest(String stockCode) {
        IntradayFeatureState state = states.get(stockCode);
        return state == null ? Optional.empty() : Optional.ofNullable(state.latest());
    }

    /**
     * 하는 일: 주어진 5분봉 시간대 안에서 마지막으로 계산된 스냅샷을 찾아요. 최신값 → 직전 칸 값 순으로 확인해요.
     * 받는 것: 종목코드, 봉 시작시각.
     * 돌려주는 것: 그 봉 시간대의 스냅샷, 둘 다 해당하지 않으면 빈 Optional(다른 시간대 값으로 대신하지 않아요).
     */
    public Optional<MarketFeatureSnapshot> at(String stockCode, Instant candleStart) {
        MarketFeatureSnapshot latest = latest(stockCode).orElse(null);
        if (inBucket(latest, candleStart)) return Optional.of(latest);
        MarketFeatureSnapshot prior = previous.get(stockCode);
        return inBucket(prior, candleStart) ? Optional.of(prior) : Optional.empty();
    }

    /** 하는 일: 스냅샷 시각이 [봉 시작, 봉 시작 + 5분) 안인지 확인해요. */
    private boolean inBucket(MarketFeatureSnapshot value, Instant start) {
        return value != null && !value.occurredAt().isBefore(start)
                && value.occurredAt().isBefore(start.plus(Duration.ofMinutes(5)));
    }

    /** 하는 일: 시각을 서울 기준 5분 단위로 내림해 칸 시작시각을 구해요. */
    private Instant bucket(Instant value) {
        java.time.ZonedDateTime time = value.atZone(java.time.ZoneId.of("Asia/Seoul"));
        return time.withMinute(time.getMinute() - time.getMinute() % 5).withSecond(0).withNano(0).toInstant();
    }

    /** 하는 일: 지금 Feature를 계산 중인 종목 수를 돌려줘요. */
    public int trackedStocks() {
        return states.size();
    }
}
