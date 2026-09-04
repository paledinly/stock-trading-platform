CREATE TABLE overnight_position_decision (
 id bigserial PRIMARY KEY,
 closing_recommendation_id bigint NOT NULL REFERENCES closing_recommendation(id),
 evaluated_at timestamp with time zone NOT NULL,
 current_price numeric(20,4),
 return_rate numeric(12,6),
 vwap numeric(20,6),
 vwap_distance_rate numeric(12,6),
 trade_strength numeric(12,6),
 ma5 numeric(20,6),
 ma20 numeric(20,6),
 ma60 numeric(20,6),
 target_hit boolean NOT NULL DEFAULT false,
 stop_hit boolean NOT NULL DEFAULT false,
 decision varchar(20) NOT NULL,
 reason_json text NOT NULL,
 calculation_version varchar(40) NOT NULL,
 created_at timestamp with time zone NOT NULL DEFAULT now(),
 CONSTRAINT ck_overnight_position_decision CHECK(decision IN ('DATA_PENDING','HOLD','EXTEND_HOLD','TAKE_PROFIT','SELL_WARNING','STOP_LOSS'))
);

CREATE INDEX idx_overnight_position_decision_recommendation_time
    ON overnight_position_decision(closing_recommendation_id, evaluated_at DESC);
CREATE INDEX idx_overnight_position_decision_decision
    ON overnight_position_decision(decision);
