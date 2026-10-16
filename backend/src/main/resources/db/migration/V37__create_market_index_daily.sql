-- Research (external factors, record only): KOSPI/KOSDAQ daily index bars from KIS FHKUP03500100,
-- used to test market-regime hypotheses over a long history and to show the previous close.
-- volume and trading_value keep KIS units as received (acml_vol, acml_tr_pbmn; thousand shares and million KRW).
CREATE TABLE market_index_daily (
    index_code varchar(10) NOT NULL,
    index_name varchar(20) NOT NULL,
    trade_date date NOT NULL,
    open numeric(14,4),
    high numeric(14,4),
    low numeric(14,4),
    close numeric(14,4) NOT NULL,
    volume bigint,
    trading_value numeric(24,4),
    fetched_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_market_index_daily PRIMARY KEY (index_code, trade_date)
);
