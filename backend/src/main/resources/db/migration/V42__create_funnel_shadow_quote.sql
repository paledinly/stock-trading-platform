-- Research shadow record of the precision funnel (2026-10-29): right after the 15:20 entry deadline, one REST quote
-- for every stock that was a Broad candidate or a precision subscription that day, so every funnel stage can be
-- measured at the same entry time (non-precision stocks have no realtime ticks). Scoring never reads it.
CREATE TABLE funnel_shadow_quote (
    session_date date NOT NULL,
    stock_id bigint NOT NULL REFERENCES stock (id),
    stock_code varchar(12) NOT NULL,
    in_broad boolean NOT NULL,
    in_precision boolean NOT NULL,
    price numeric(18, 4),
    change_rate numeric(10, 4),
    accumulated_trading_value numeric(24, 4),
    received_at timestamp with time zone NOT NULL,
    error varchar(200),
    PRIMARY KEY (session_date, stock_id)
);
