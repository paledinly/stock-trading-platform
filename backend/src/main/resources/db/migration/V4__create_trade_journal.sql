CREATE TABLE trade (
    id bigserial PRIMARY KEY,
    owner_id bigint NOT NULL,
    stock_id bigint NOT NULL REFERENCES stock(id),
    trade_type varchar(10) NOT NULL,
    traded_at timestamp with time zone NOT NULL,
    price numeric(20,4) NOT NULL,
    quantity bigint NOT NULL,
    amount numeric(20,4) NOT NULL,
    idempotency_key varchar(100),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_trade_type CHECK (trade_type IN ('BUY', 'SELL')),
    CONSTRAINT ck_trade_price CHECK (price > 0),
    CONSTRAINT ck_trade_quantity CHECK (quantity > 0),
    CONSTRAINT ck_trade_amount CHECK (amount = price * quantity),
    CONSTRAINT uk_trade_owner_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE TABLE investment_journal (
    id bigserial PRIMARY KEY,
    trade_id bigint NOT NULL UNIQUE REFERENCES trade(id) ON DELETE CASCADE,
    memo text,
    target_price numeric(20,4),
    stop_loss_price numeric(20,4),
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_journal_target CHECK (target_price IS NULL OR target_price > 0),
    CONSTRAINT ck_journal_stop CHECK (stop_loss_price IS NULL OR stop_loss_price > 0)
);

CREATE TABLE trade_reason (
    trade_id bigint NOT NULL REFERENCES trade(id) ON DELETE CASCADE,
    reason_code varchar(40) NOT NULL,
    custom_reason varchar(200),
    PRIMARY KEY (trade_id, reason_code),
    CONSTRAINT ck_custom_reason CHECK (reason_code <> 'CUSTOM' OR custom_reason IS NOT NULL)
);

CREATE INDEX idx_trade_owner_time ON trade (owner_id, traded_at DESC, id DESC);
CREATE INDEX idx_trade_owner_stock_time ON trade (owner_id, stock_id, traded_at, id);
