-- Adjusted (수정주가) daily bars for research over a point-in-time universe of all listed non-ETF/ETN stocks.
-- Unlike stock_candle, a row is rewritten when KIS revises the adjusted history after a corporate action;
-- fetched_at is the last write. Operational 1D candles in stock_candle keep original prices and are unaffected.
CREATE TABLE daily_adjusted_candle (
    stock_id bigint NOT NULL REFERENCES stock(id),
    trade_date date NOT NULL,
    open_price numeric(20,4) NOT NULL,
    high_price numeric(20,4) NOT NULL,
    low_price numeric(20,4) NOT NULL,
    close_price numeric(20,4) NOT NULL,
    volume bigint NOT NULL,
    trading_value numeric(24,4) NOT NULL,
    fetched_at timestamp with time zone NOT NULL,
    PRIMARY KEY (stock_id, trade_date)
);
CREATE INDEX idx_daily_adjusted_candle_date ON daily_adjusted_candle(trade_date);

-- Per-stock sweep progress so an interrupted sweep resumes and a fully covered stock only needs a recent window.
CREATE TABLE daily_adjusted_sweep_state (
    stock_id bigint PRIMARY KEY REFERENCES stock(id),
    covered_from date NOT NULL,
    covered_through date NOT NULL,
    swept_at timestamp with time zone NOT NULL
);
