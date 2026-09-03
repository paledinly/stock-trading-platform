# Phase 8 Backtesting Report

Date: 2026-09-03

## Goal

Phase 8 adds scanner backtesting using stored 5-minute candles.

The implementation reuses the existing scanner evaluator and returns virtual detections with virtual performance. It
does not insert rows into `scanner_detection` or `detection_performance`.

## Completed

- Added Backtesting backend API
- Added `BacktestService`
- Reused `ScannerEvaluator`
- Evaluated scanner settings against historical 5-minute candles
- Generated virtual detections
- Calculated virtual performance:
  - 5-minute return
  - 30-minute return
  - 60-minute return
  - max return
  - max drawdown
- Added setting-level strategy summary
- Added historical feature proxy from candles:
  - VWAP
  - VWAP distance
  - volume ratio
  - turnover ratio approximation
  - day high distance
- Added Backtesting web workspace
- Added stock code, date range and scanner setting controls
- Added strategy comparison table
- Added virtual detection list
- Added frontend test coverage

## API

Endpoint:

```text
GET /api/v1/backtests/run?stockCode=&from=&to=&settingId=&limit=
```

Response includes:

- stock metadata
- evaluated candle count
- virtual detection count
- setting summaries
- limited virtual detection list

## Design Notes

- Backtesting is read-only.
- The scanner evaluator is reused instead of duplicating detection conditions.
- Current and prior candles only are used for detection decisions.
- Future candles are used only after a virtual detection to calculate performance.
- Realtime-only fields such as trade strength and buy/sell delta are not invented.
- Historical VWAP-related features are derived from stored candles as a conservative proxy.

## Limitations

- Backtesting quality depends on stored candle coverage.
- If the requested range starts after market open, daily trading value gates can be underestimated because earlier
  candles may not be loaded.
- Exact tick-level behavior, order-book behavior and intrabar first-touch order are not modeled.
- Virtual detections are not persisted yet.

## Validation

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## Recommended Next Step

Before adding more phases, run a live-market validation pass:

- confirm KIS field mapping
- confirm backfilled candle coverage
- compare realtime scanner detections with backtest replay on the same day
- confirm Phase 4 Opportunity/Risk score distributions
- tune Phase 7 Broad Scan scoring limits
