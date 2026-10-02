CREATE TABLE closing_microstructure_minute (
    kind varchar(30) NOT NULL,
    symbol varchar(20) NOT NULL,
    start_time timestamp with time zone NOT NULL,
    finalized_at timestamp with time zone NOT NULL,
    payload text NOT NULL,
    PRIMARY KEY (kind, symbol, start_time)
);
CREATE INDEX ix_closing_microstructure_time ON closing_microstructure_minute(start_time, symbol);
