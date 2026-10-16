-- Realtime coverage gaps caused by an intraday backend restart or WebSocket disconnect (record only).
-- One row per stock and resume: the 5M buckets from lost_from through the resume bucket could not be fully
-- received in realtime and were refilled from KIS minute candles (source BACKFILL) where KIS had trades.
-- Times are host receipt times (resumed_at = subscription ack), not KIS source times.
CREATE TABLE realtime_coverage_gap (
    session_date date NOT NULL,
    stock_code varchar(12) NOT NULL,
    cause varchar(20) NOT NULL,
    lost_from timestamp with time zone NOT NULL,
    resumed_at timestamp with time zone NOT NULL,
    target_bars integer NOT NULL,
    repaired_bars integer NOT NULL,
    still_missing integer NOT NULL,
    attempts integer NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_realtime_coverage_gap PRIMARY KEY (stock_code, resumed_at)
);
CREATE INDEX ix_realtime_coverage_gap_date ON realtime_coverage_gap(session_date);
