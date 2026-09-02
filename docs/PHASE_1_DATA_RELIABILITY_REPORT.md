# Phase 1 Data Reliability Hardening

## Scope

- KIS same-day minute-candle backfill and five-minute aggregation
- Closed five-minute candle gap detection on KRX/Asia-Seoul session boundaries
- Backfill/realtime merge precedence using the existing candle unique key
- Watchlist-driven realtime subscription bootstrap and lifecycle synchronization
- Configurable KIS WebSocket subscription capacity and unsubscribe support
- Expanded realtime, candle, scanner, Redis and backfill diagnostics
- Scanner detection timestamps based on actual evaluation time instead of candle bucket start

## Data rules

- Only closed five-minute buckets are considered missing.
- A confirmed realtime candle cannot be overwritten by backfill data.
- Realtime revisions may replace backfilled candles.
- `stock_candle.source` is normalized to `REALTIME` or `BACKFILL`.
- Backfill is throttled per stock and bounded by a configurable request count.

## Configuration

- `MARKET_BACKFILL_ENABLED` (default `true`)
- `MARKET_BACKFILL_REFRESH_INTERVAL` (default `1m`)
- `MARKET_BACKFILL_MAX_REQUESTS` (default `14`)
- `KIS_MINUTE_RATE_LIMIT` (default `5` requests/second)
- `KIS_WEBSOCKET_SUBSCRIPTION_LIMIT` (default `41`)

## Operational boundary

Backfill is invoked when today's five-minute candles are queried. It does not backfill every stock in the market. The KIS subscription limit still prevents market-wide tick ingestion; later broad scanning requires a REST-based universe reduction strategy.
