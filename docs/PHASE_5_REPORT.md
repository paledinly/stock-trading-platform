# Phase 5 Completion Report

Date: 2026-08-18

## Implemented

- KIS WebSocket approval and `H0STCNT0` subscription client with reconnect and ping handling
- Explicit/selected-stock subscription registry; no unsupported whole-market subscription assumption
- Normalized event-time market ticks and duplicate-sequence protection
- Redis quote snapshot with process-local fallback and market-session TTL
- KRX regular-session 5-minute OHLCV aggregation
- Cumulative-volume/value delta calculation with reset fallback
- Watermark close, previous-bucket late revision and idempotent PostgreSQL candle persistence
- Candle REST API and replayable SSE event envelope
- Web SSE bridge that updates TanStack Query quote state

## API

- `GET /api/v1/stream`
- `GET/POST /api/v1/market/subscriptions[/{stockCode}]`
- `GET /api/v1/stocks/{stockCode}/candles?timeFrame=5M&from=&to=`

## Operational boundary

Set `MARKET_REALTIME_ENABLED=true` to open the KIS production WebSocket. Account-specific concurrent subscription limits are not assumed and still require a market-hours soak test. Only selected or explicitly requested stocks are subscribed. No order endpoint exists.
