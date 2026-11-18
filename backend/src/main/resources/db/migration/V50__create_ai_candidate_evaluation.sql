-- AI candidate evaluation (ADR-014, docs/AI_CANDIDATE_EVALUATION.md). The program fixes a pool (top 40 of the premarket
-- volatility list) and the card text it showed; an LLM grades every pool stock; the first on-time evaluation per date
-- is the counted one. Outcomes are measured for the whole pool so baskets (pool, AI A, avoid, random, rule) compare.

-- One pool per target date, frozen at first creation together with the card text given to the AI.
CREATE TABLE ai_candidate_pool (
    target_date date PRIMARY KEY,
    pool_id varchar(16) NOT NULL,
    data_date date NOT NULL,
    created_at timestamp with time zone NOT NULL,
    card_text text NOT NULL
);

CREATE TABLE ai_candidate_pool_stock (
    target_date date NOT NULL REFERENCES ai_candidate_pool (target_date),
    pool_rank int NOT NULL,
    stock_id bigint NOT NULL,
    stock_code varchar(12) NOT NULL,
    stock_name varchar(120) NOT NULL,
    -- 20-day standard deviation of daily close changes (0.05 = 5%)
    volatility numeric(12, 6),
    -- previous day's trading value / average of the 20 rows before it (RULE basket key)
    value_ratio20 numeric(12, 6),
    CONSTRAINT pk_ai_candidate_pool_stock PRIMARY KEY (target_date, stock_code)
);

-- A pasted evaluation, stored as received. Revisions accumulate; only the first on-time one is counted.
CREATE TABLE ai_candidate_eval (
    id bigserial PRIMARY KEY,
    target_date date NOT NULL REFERENCES ai_candidate_pool (target_date),
    revision int NOT NULL,
    pool_id varchar(16) NOT NULL,
    schema_version varchar(40) NOT NULL,
    analyzed_at timestamp with time zone,
    submitted_at timestamp with time zone NOT NULL,
    on_time boolean NOT NULL,
    model_name varchar(80) NOT NULL,
    model_version varchar(80),
    prompt_version varchar(80) NOT NULL,
    market_outlook varchar(16) NOT NULL,
    raw_json text NOT NULL,
    warnings text,
    CONSTRAINT uq_ai_candidate_eval_revision UNIQUE (target_date, revision)
);

-- One row per pool stock per evaluation. Entry and exit values are the AI's; v1 scoring does not use them.
CREATE TABLE ai_candidate_grade (
    eval_id bigint NOT NULL REFERENCES ai_candidate_eval (id),
    stock_code varchar(12) NOT NULL,
    stock_id bigint NOT NULL,
    grade varchar(8) NOT NULL,
    catalyst int NOT NULL,
    flow int NOT NULL,
    trend int NOT NULL,
    overheat int NOT NULL,
    disclosure_risk int NOT NULL,
    risk_flags varchar(200),
    confidence int NOT NULL,
    entry_type varchar(16),
    stop_pct numeric(8, 3),
    target_pct numeric(8, 3),
    time_exit varchar(12),
    used_external_info boolean NOT NULL,
    CONSTRAINT pk_ai_candidate_grade PRIMARY KEY (eval_id, stock_code)
);

-- Measured from daily_adjusted_candle after the close for every pool stock. Percent values (2 = 2%).
CREATE TABLE ai_candidate_outcome (
    target_date date NOT NULL,
    stock_id bigint NOT NULL,
    status varchar(16) NOT NULL,
    prev_close numeric(19, 4),
    open_price numeric(19, 4),
    close_price numeric(19, 4),
    gap_pct numeric(12, 6),
    open_close_pct numeric(12, 6),
    net_open_close_pct numeric(12, 6),
    cost_assumption text,
    evaluated_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_ai_candidate_outcome PRIMARY KEY (target_date, stock_id)
);
