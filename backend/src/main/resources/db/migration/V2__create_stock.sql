CREATE TABLE stock (
    id bigserial PRIMARY KEY,
    stock_code varchar(12) NOT NULL UNIQUE,
    standard_code varchar(20),
    stock_name varchar(120) NOT NULL,
    market varchar(20) NOT NULL,
    market_type varchar(30) NOT NULL,
    is_etf boolean NOT NULL DEFAULT false,
    is_etn boolean NOT NULL DEFAULT false,
    is_managed boolean NOT NULL DEFAULT false,
    is_trading_halted boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    master_synced_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_name ON stock (stock_name);
CREATE INDEX idx_stock_market_active ON stock (market, is_active);
