-- Research history of delisted stocks (2026-11-05). The stock master and every other table only hold stocks listed
-- today, so research on 2018~ is survivorship-biased. The list comes from KRX KIND "상장폐지현황" (loaded through the
-- API); prices and net buying come from the same KIS TR as investor_daily_flow (FHPTJ04160001), which still answers
-- for delisted codes. Scoring and selection never read these tables.
CREATE TABLE delisted_stock (
    stock_code varchar(12) PRIMARY KEY,
    stock_name varchar(200),
    market varchar(20),
    delisted_date date NOT NULL,
    reason text,
    oldest_date date,
    newest_date date,
    complete boolean NOT NULL DEFAULT false,
    updated_at timestamp with time zone NOT NULL
);

-- One row per (stock, trading day) as KIS returns it: raw (unadjusted) prices in KRW, volume in shares, trading value
-- in KRW, net-buy amounts in millions of KRW (negative = net selling). Halted days come back with volume 0.
CREATE TABLE delisted_daily_history (
    stock_code varchar(12) NOT NULL REFERENCES delisted_stock (stock_code),
    trade_date date NOT NULL,
    open_price bigint,
    high_price bigint,
    low_price bigint,
    close_price bigint,
    volume bigint,
    trading_value bigint,
    foreign_amount bigint,
    institution_amount bigint,
    individual_amount bigint,
    pension_amount bigint,
    other_corp_amount bigint,
    fetched_at timestamp with time zone NOT NULL,
    PRIMARY KEY (stock_code, trade_date)
);
