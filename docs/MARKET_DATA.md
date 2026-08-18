# Market Data

Phase 5 ingests normalized KIS H0STCNT0 ticks for selected stocks. Tick data remains ephemeral in Redis/process memory; finalized 5-minute candles are persisted in PostgreSQL and streamed to clients through SSE.

The production WebSocket is enabled with MARKET_REALTIME_ENABLED. Account-specific subscription limits require a market-hours soak test; the service never assumes whole-market subscription capacity.
