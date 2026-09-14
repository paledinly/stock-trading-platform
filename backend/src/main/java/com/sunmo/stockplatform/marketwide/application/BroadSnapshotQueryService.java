package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.SnapshotResponse;
import com.sunmo.stockplatform.marketwide.domain.MarketBroadSnapshot;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class BroadSnapshotQueryService {
    private final MarketBroadSnapshotRepository snapshots;

    public BroadSnapshotQueryService(MarketBroadSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Transactional(readOnly = true)
    public List<SnapshotResponse> history(LocalDate date, String stockCode, int limit) {
        String normalizedCode = stockCode == null || stockCode.isBlank() ? null : stockCode.trim();
        return snapshots.findHistory(date, normalizedCode, PageRequest.of(0, limit)).stream()
                .map(this::response)
                .toList();
    }

    private SnapshotResponse response(MarketBroadSnapshot snapshot) {
        var stock = snapshot.getStock();
        return new SnapshotResponse(snapshot.getId(), snapshot.getSessionDate(), snapshot.getCapturedAt(),
                stock.getStockCode(), stock.getStockName(), stock.getMarket().name(), snapshot.getCurrentPrice(),
                snapshot.getChangeRate(), snapshot.getAccumulatedVolume(), snapshot.getAccumulatedTradingValue(),
                snapshot.getDayOpen(), snapshot.getDayHigh(), snapshot.getDayLow(), snapshot.getTradeStrength(),
                snapshot.getBroadScore(), snapshot.getRankingSources(), snapshot.getDataQuality().name(),
                snapshot.getCollectionStatus().name(), snapshot.getExclusionReason(), snapshot.getQuotedAt(),
                snapshot.getSourceVersion(), snapshot.getQuoteSource());
    }
}
