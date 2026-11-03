-- Research daily net buying by investor type per stock (2026-11-03), from KIS "종목별 투자자매매동향(일별)"
-- (FHPTJ04160001, 30 trading days per call, available back to at least 2015). Quantities are shares, amounts are
-- millions of KRW as KIS reports them (negative = net selling). Confirmed after the close; scoring never reads it.
CREATE TABLE investor_daily_flow (
    stock_id bigint NOT NULL REFERENCES stock (id),
    trade_date date NOT NULL,
    foreign_qty bigint,
    institution_qty bigint,
    individual_qty bigint,
    foreign_amount bigint,
    institution_amount bigint,
    individual_amount bigint,
    securities_amount bigint,
    trust_amount bigint,
    private_fund_amount bigint,
    bank_amount bigint,
    insurance_amount bigint,
    merchant_bank_amount bigint,
    pension_amount bigint,
    other_corp_amount bigint,
    fetched_at timestamp with time zone NOT NULL,
    PRIMARY KEY (stock_id, trade_date)
);

-- Per stock: the date range already fetched, and whether the history reached its floor (or the stock's first day).
CREATE TABLE investor_flow_state (
    stock_id bigint PRIMARY KEY REFERENCES stock (id),
    oldest_date date,
    newest_date date,
    complete boolean NOT NULL DEFAULT false,
    updated_at timestamp with time zone NOT NULL
);
