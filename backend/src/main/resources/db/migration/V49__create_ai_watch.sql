-- AI 장전 계획 2단계: 장중 실시간 감시와 가상 매수 기록 (2026-11-10, docs/AI_PREMARKET_PHASE2.md).
-- 실주문은 없다. 틱은 저장하지 않고 상태 변화와 가상 진입·청산만 남긴다.

-- 날짜별 감시 한 번. 계획 판(plan_id)과 구독 결과를 남긴다.
CREATE TABLE ai_watch_session (
    trade_date date PRIMARY KEY,
    plan_id bigint NOT NULL REFERENCES ai_premarket_plan (id),
    trade_decision varchar(20) NOT NULL,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    pick_count integer NOT NULL,
    subscribed_count integer NOT NULL,
    unsubscribed_codes text,
    position_amount numeric(20,0) NOT NULL,
    rule_version varchar(40) NOT NULL
);

-- 종목·규칙별 상태 변화. 원천 체결시각(source_at)·수신시각(received_at)·평가시각(evaluated_at)을 따로 둔다.
CREATE TABLE ai_signal_event (
    id bigserial PRIMARY KEY,
    trade_date date NOT NULL,
    pick_id bigint NOT NULL REFERENCES ai_premarket_pick (id),
    stock_code varchar(12) NOT NULL,
    rule varchar(1) NOT NULL,
    from_state varchar(20),
    to_state varchar(20) NOT NULL,
    reasons text,
    indicators text,
    source_at timestamp with time zone,
    received_at timestamp with time zone,
    evaluated_at timestamp with time zone NOT NULL
);
CREATE INDEX idx_ai_signal_event_date ON ai_signal_event (trade_date, stock_code);

-- 가상 진입 한 건에 두 가지 청산(E1 구조 손절+장 마감, E2 +3%/-2%)을 함께 기록한다.
-- shadow: 그날 계획이 TRADE가 아니라서(감시만·매매 안 함·자료 부족) 실제라면 사지 않았을 진입.
CREATE TABLE ai_virtual_position (
    id bigserial PRIMARY KEY,
    trade_date date NOT NULL,
    pick_id bigint NOT NULL REFERENCES ai_premarket_pick (id),
    stock_code varchar(12) NOT NULL,
    rule varchar(1) NOT NULL,
    shadow boolean NOT NULL,
    signal_at timestamp with time zone NOT NULL,
    entry_source_at timestamp with time zone NOT NULL,
    entry_received_at timestamp with time zone NOT NULL,
    entry_tick_price numeric(20,4) NOT NULL,
    half_spread_pct numeric(10,4) NOT NULL,
    spread_source varchar(20) NOT NULL,
    entry_price numeric(20,4) NOT NULL,
    quantity bigint NOT NULL,
    capacity_limited boolean NOT NULL,
    stop_price numeric(20,4) NOT NULL,
    e1_exit_price numeric(20,4),
    e1_exit_at timestamp with time zone,
    e1_reason varchar(20),
    e1_net_pct numeric(12,4),
    e2_exit_price numeric(20,4),
    e2_exit_at timestamp with time zone,
    e2_reason varchar(20),
    e2_net_pct numeric(12,4),
    max_price numeric(20,4) NOT NULL,
    min_price numeric(20,4) NOT NULL,
    day_open_price numeric(20,4),
    tracking_gap boolean NOT NULL DEFAULT false,
    cost_assumption text NOT NULL,
    UNIQUE (trade_date, pick_id, rule)
);
