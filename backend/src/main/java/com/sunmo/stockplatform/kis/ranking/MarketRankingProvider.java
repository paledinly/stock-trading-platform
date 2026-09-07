package com.sunmo.stockplatform.kis.ranking;

import com.sunmo.stockplatform.stock.domain.Market;
import java.util.List;

public interface MarketRankingProvider {
    List<KisRankingEntry> fetch(RankingType type, Market market, int limit);
}
