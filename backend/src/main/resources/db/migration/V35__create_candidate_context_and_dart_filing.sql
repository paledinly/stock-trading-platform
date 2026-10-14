-- Record-only external context for closing candidates (not used by scoring or selection).
-- Market warning / caution / VI / short-term overheat state of every candidate of the official run, read from the KIS
-- current-price response right after the 15:15 evaluation. received_at is the host receipt time.
CREATE TABLE closing_candidate_context (
    run_id bigint NOT NULL REFERENCES closing_recommendation_run(id),
    stock_code varchar(12) NOT NULL,
    received_at timestamp with time zone NOT NULL,
    current_price numeric(20,4),
    change_rate numeric(12,6),
    market_warning_code varchar(4),
    investment_caution varchar(2),
    vi_code varchar(4),
    short_term_overheat varchar(2),
    liquidation_trading varchar(2),
    managed_code varchar(4),
    temporary_halt varchar(2),
    status_code varchar(4),
    error text,
    PRIMARY KEY (run_id, stock_code)
);

-- DART disclosure list for listed companies. DART gives only the receipt date (rcept_dt), not a time, so a filing is
-- known at a 15:15 decision only when rcept_dt is before the decision date. fetched_at is the host receipt time.
CREATE TABLE dart_filing (
    rcept_no varchar(20) PRIMARY KEY,
    rcept_dt date NOT NULL,
    corp_cls varchar(2),
    corp_code varchar(10),
    corp_name varchar(200),
    stock_code varchar(12),
    report_nm varchar(500) NOT NULL,
    flr_nm varchar(200),
    rm varchar(50),
    fetched_at timestamp with time zone NOT NULL
);
CREATE INDEX idx_dart_filing_stock_date ON dart_filing(stock_code, rcept_dt);
