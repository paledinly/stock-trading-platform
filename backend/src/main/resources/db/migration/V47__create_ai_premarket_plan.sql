-- AI pre-market plans (ADR-012). A person pastes the JSON an LLM wrote before the open; it is stored as received and
-- never changed. Submitting again for the same target date adds a revision instead of overwriting.
-- Market data measured afterwards lives in ai_premarket_outcome, apart from anything the AI wrote.
CREATE TABLE ai_premarket_plan (
    id bigserial PRIMARY KEY,
    target_date date NOT NULL,
    revision int NOT NULL,
    schema_version varchar(40) NOT NULL,
    analyzed_at timestamp with time zone NOT NULL,
    valid_until timestamp with time zone NOT NULL,
    submitted_at timestamp with time zone NOT NULL,
    -- true when submitted before the regular session opened on target_date; only on-time plans are scored
    on_time boolean NOT NULL,
    trade_decision varchar(16) NOT NULL,
    market_outlook varchar(16) NOT NULL,
    model_name varchar(80) NOT NULL,
    model_version varchar(80),
    prompt_version varchar(80) NOT NULL,
    raw_json text NOT NULL,
    warnings text,
    CONSTRAINT uq_ai_premarket_plan_revision UNIQUE (target_date, revision)
);

-- Stocks named in a plan: kind PICK (buy candidate) or AVOID (do-not-buy). AI levels and conditions stay in raw_json.
CREATE TABLE ai_premarket_pick (
    id bigserial PRIMARY KEY,
    plan_id bigint NOT NULL REFERENCES ai_premarket_plan (id),
    kind varchar(8) NOT NULL,
    stock_id bigint NOT NULL,
    stock_code varchar(12) NOT NULL,
    stock_name varchar(120) NOT NULL,
    priority int,
    reason text,
    CONSTRAINT uq_ai_premarket_pick UNIQUE (plan_id, kind, stock_code)
);

-- Measured from daily_adjusted_candle after the close. Percent values (2 = 2%). Universe = stocks whose average
-- trading value over the previous 20 rows is at least 1 billion won, same day, equal weight.
CREATE TABLE ai_premarket_outcome (
    pick_id bigint PRIMARY KEY REFERENCES ai_premarket_pick (id),
    trade_date date NOT NULL,
    status varchar(16) NOT NULL,
    prev_close numeric(19, 4),
    open_price numeric(19, 4),
    high_price numeric(19, 4),
    low_price numeric(19, 4),
    close_price numeric(19, 4),
    gap_pct numeric(12, 6),
    open_close_pct numeric(12, 6),
    high_pct numeric(12, 6),
    low_pct numeric(12, 6),
    close_close_pct numeric(12, 6),
    net_open_close_pct numeric(12, 6),
    universe_open_close_pct numeric(12, 6),
    universe_gap_pct numeric(12, 6),
    universe_count int,
    excess_open_close_pct numeric(12, 6),
    cost_assumption text,
    evaluated_at timestamp with time zone NOT NULL
);

CREATE INDEX idx_ai_premarket_outcome_date ON ai_premarket_outcome (trade_date);

-- The universe average per scored date, kept so NO_TRADE days (often without stocks) can be compared too.
CREATE TABLE ai_premarket_universe (
    trade_date date PRIMARY KEY,
    open_close_pct numeric(12, 6),
    gap_pct numeric(12, 6),
    stock_count int NOT NULL,
    evaluated_at timestamp with time zone NOT NULL
);
