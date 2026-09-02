# Phase 2A Performance Reliability

## Implemented

- Pending performance rows are loaded into an in-memory registry at startup.
- Realtime ticks update the registry without querying PostgreSQL per tick.
- Dirty performance rows are persisted in one-second batches.
- Stored confirmed five-minute candles recover milestones after a restart.
- Recovery excludes the candle containing the detection unless detection occurred exactly at its boundary, preventing pre-detection highs and lows from contaminating results.
- Prices timestamped before the detection are ignored.
- MFE and MAE are stored explicitly while legacy max-return fields remain available.
- Previous-session pending rows, and same-day rows loaded after market close, finalize as `COMPLETED` or `DATA_MISSING` according to available observations.
- Diagnostics expose pending performance count and the latest batch flush.

## Calculation version

New and pending calculations use `performance-v2`. Completed historical `performance-v1` rows remain unchanged, so analytics may report `mixed` when both versions are selected.

## Recovery boundary

Live ticks remain the most precise observation source. Restart recovery uses confirmed five-minute candle high, low and close values. It deliberately sacrifices the partial detection candle rather than include prices that occurred before the detection.
