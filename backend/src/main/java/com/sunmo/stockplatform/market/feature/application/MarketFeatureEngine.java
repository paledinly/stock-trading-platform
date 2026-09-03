package com.sunmo.stockplatform.market.feature.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketFeatureEngine {
    private final Map<String, IntradayFeatureState> states = new ConcurrentHashMap<>();
    private final RealtimeDiagnostics diagnostics;

    public MarketFeatureEngine(RealtimeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public MarketFeatureSnapshot onTick(MarketTick tick) {
        MarketFeatureSnapshot snapshot = states.computeIfAbsent(tick.stockCode(), IntradayFeatureState::new)
                .accept(tick);
        diagnostics.featureSnapshot(states.size());
        return snapshot;
    }

    public Optional<MarketFeatureSnapshot> latest(String stockCode) {
        IntradayFeatureState state = states.get(stockCode);
        return state == null ? Optional.empty() : Optional.ofNullable(state.latest());
    }

    public int trackedStocks() {
        return states.size();
    }
}
