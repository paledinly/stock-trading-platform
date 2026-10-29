-- Research intraday 5-minute bars reconstructed from KIS past minute charts (2026-10-29).
-- One trading day at a time for every stock that was tradable that day (previous day trading value >= 500M KRW or
-- same day >= 1bn KRW), so intraday patterns can be studied without pre-selecting likely winners. Raw (unadjusted)
-- prices in KRW. Scoring never reads these tables.
CREATE TABLE intraday_research_bar (
    stock_id bigint NOT NULL REFERENCES stock (id),
    start_time timestamp with time zone NOT NULL,
    open_price integer NOT NULL,
    high_price integer NOT NULL,
    low_price integer NOT NULL,
    close_price integer NOT NULL,
    volume bigint NOT NULL,
    trading_value bigint NOT NULL,
    PRIMARY KEY (stock_id, start_time)
);
-- No separate time index: analysis streams rows in primary-key order (stock, time), which the key already serves.

-- One row per (day, stock) fetched, including days with no bars (halted), so a resumed run never refetches them.
CREATE TABLE intraday_research_state (
    trade_date date NOT NULL,
    stock_id bigint NOT NULL REFERENCES stock (id),
    bars integer NOT NULL,
    fetched_at timestamp with time zone NOT NULL,
    PRIMARY KEY (trade_date, stock_id)
);
