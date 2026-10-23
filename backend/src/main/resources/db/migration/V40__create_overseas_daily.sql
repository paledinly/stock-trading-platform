-- Research (external factors, record only): overseas daily bars for US indices, USD/KRW and the EWY ETF
-- from KIS FHKST03030100 (index/FX) and HHDFS76240000 (ETF). Not used in scoring or selection.
-- trade_date is the exchange-local trading date. A US bar for date D is final only after that session closes
-- (about D+1 05:00~06:00 KST); fetched_at is the host receipt time, not a source time.
CREATE TABLE overseas_daily (
    symbol varchar(20) NOT NULL,
    source_code varchar(20) NOT NULL,
    trade_date date NOT NULL,
    open numeric(18,6),
    high numeric(18,6),
    low numeric(18,6),
    close numeric(18,6) NOT NULL,
    volume bigint,
    fetched_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_overseas_daily PRIMARY KEY (symbol, trade_date)
);
