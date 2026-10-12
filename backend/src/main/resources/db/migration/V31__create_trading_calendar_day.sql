CREATE TABLE trading_calendar_day (
    trade_date date PRIMARY KEY,
    market_open boolean NOT NULL,
    source varchar(20) NOT NULL,
    fetched_at timestamp with time zone NOT NULL
);
