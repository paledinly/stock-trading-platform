# Scanner

Phase 6 implements volume-surge and five-minute price-rise evaluation from realtime five-minute candles. Six finalized history buckets are required. Redis provides atomic cooldown and ten-minute ranking state; PostgreSQL is the immutable detection history.
