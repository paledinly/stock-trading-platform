-- Research (audit/2026-10-14-limit-up-entry): stocks up 20%+ sampled at 15:16:15 and 15:18:30 with their order book,
-- to tell whether a near-limit stock could actually be bought before the 15:20 entry deadline.
-- ranking_received_at and book_received_at are host receipt times; book_source_at is the KIS order book time.
CREATE TABLE limit_up_watch (
    id bigserial PRIMARY KEY,
    session_date date NOT NULL,
    sample_label varchar(10) NOT NULL,
    stock_code varchar(12) NOT NULL,
    stock_id bigint REFERENCES stock(id),
    ranking_received_at timestamp with time zone NOT NULL,
    current_price numeric(20,4),
    change_rate numeric(12,6),
    day_high numeric(20,4),
    book_source_at timestamp with time zone,
    book_received_at timestamp with time zone,
    best_ask numeric(20,4),
    best_ask_qty numeric(20,4),
    best_bid numeric(20,4),
    best_bid_qty numeric(20,4),
    total_ask_qty numeric(24,4),
    total_bid_qty numeric(24,4),
    error text,
    CONSTRAINT uk_limit_up_watch UNIQUE(session_date, sample_label, stock_code)
);
CREATE INDEX idx_limit_up_watch_date ON limit_up_watch(session_date);
