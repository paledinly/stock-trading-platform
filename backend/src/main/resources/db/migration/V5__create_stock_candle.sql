CREATE TABLE stock_candle (
    id bigserial PRIMARY KEY,
    stock_id bigint NOT NULL REFERENCES stock(id),
    timeframe varchar(10) NOT NULL,
    start_time timestamp with time zone NOT NULL,
    open numeric(20,4) NOT NULL,
    high numeric(20,4) NOT NULL,
    low numeric(20,4) NOT NULL,
    close numeric(20,4) NOT NULL,
    volume bigint NOT NULL,
    trading_value numeric(20,4) NOT NULL,
    is_final boolean NOT NULL,
    revision integer NOT NULL DEFAULT 0,
    source varchar(20) NOT NULL,
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_stock_candle_bucket UNIQUE (stock_id, timeframe, start_time),
    CONSTRAINT ck_stock_candle_prices CHECK (open > 0 AND high >= low AND close > 0),
    CONSTRAINT ck_stock_candle_volume CHECK (volume >= 0)
);
CREATE INDEX idx_stock_candle_lookup ON stock_candle (stock_id, timeframe, start_time DESC);
