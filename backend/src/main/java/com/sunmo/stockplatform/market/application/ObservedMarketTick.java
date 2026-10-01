package com.sunmo.stockplatform.market.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import java.time.Instant;

/** Shared observation; source time and gateway receipt time have different meanings. */
public record ObservedMarketTick(MarketTick tick, MarketFeatureSnapshot feature, Instant receivedAt) {}
