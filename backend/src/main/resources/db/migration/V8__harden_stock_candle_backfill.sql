ALTER TABLE stock_candle ADD COLUMN created_at timestamp with time zone NOT NULL DEFAULT now();

UPDATE stock_candle SET source = 'REALTIME' WHERE source = 'KIS_WS';

ALTER TABLE stock_candle
    ADD CONSTRAINT ck_stock_candle_source CHECK (source IN ('REALTIME', 'BACKFILL'));

CREATE INDEX idx_stock_candle_source_time ON stock_candle (source, start_time DESC);
