# Phase 2B Feature Engine

Date: 2026-09-03

## Implemented

- Expanded KIS H0STCNT0 realtime tick parsing for open, high, low, trade strength, cumulative buy/sell volume, buy ratio, trading halt flag, VI standard price and turnover rate.
- Extended `MarketTick` with Phase 2B fields while preserving the previous constructor for existing candle, quote and test code.
- Added `MarketFeatureSnapshot` as the common feature contract for scanner, analytics and future backtesting.
- Added in-memory `MarketFeatureEngine` and `IntradayFeatureState` for per-stock intraday calculations.
- Calculated VWAP, VWAP distance, VWAP slope, current 5-minute volume ratio, current 5-minute turnover ratio, buy/sell volume delta and day-high distance.
- Added latest feature lookup API: `GET /api/v1/stocks/{stockCode}/features/latest`.
- Persisted detection-time feature snapshots on `scanner_detection` with `feature_snapshot` and `feature_version`.
- Added realtime diagnostics for feature snapshot count, latest feature update time and tracked stock count.
- Added Flyway `V10__add_market_feature_snapshot.sql`.

## Data rules

- The feature engine is process-local and resets state when a new business date is observed for a stock.
- Duplicate or older tick sequences do not mutate the latest feature state.
- Volume and turnover ratios compare the current 5-minute bucket against up to six completed buckets observed by the current process.
- VWAP prefers KIS cumulative trading value divided by cumulative volume.
- Buy/sell deltas require cumulative side-volume fields; the first observation for a stock produces zero side deltas.
- Feature snapshots are informational and do not change order behavior.

## Boundary

- Phase 2B does not add new scanner types.
- Phase 2B does not implement Opportunity/Risk Score.
- Phase 2B does not make the feature state durable across restarts.
- Historical backfill candles are not used to reconstruct feature state yet.
- KIS field positions should still be validated during market hours against real H0STCNT0 frames.

## Verification

```text
Backend tests: passed
Gradle: BUILD SUCCESSFUL
git diff --check: passed
```
