CREATE TABLE closing_recommendation (
 id bigserial PRIMARY KEY,
 recommendation_date date NOT NULL,
 generated_at timestamp with time zone NOT NULL,
 stock_id bigint NOT NULL REFERENCES stock(id),
 source_detection_id bigint NOT NULL REFERENCES scanner_detection(id),
 scanner_type varchar(30) NOT NULL,
 rank_no integer NOT NULL,
 recommendation_score numeric(8,3) NOT NULL,
 buy_reference_price numeric(20,4) NOT NULL,
 opportunity_score numeric(6,3),
 risk_score numeric(6,3),
 daily_trading_value numeric(20,4),
 five_minute_change_rate numeric(12,6),
 volume_ratio numeric(12,6),
 recommendation_reason text NOT NULL,
 risk_reason text NOT NULL,
 feature_snapshot text,
 detection_reason text,
 strategy_version varchar(40) NOT NULL,
 status varchar(20) NOT NULL,
 created_at timestamp with time zone NOT NULL DEFAULT now(),
 updated_at timestamp with time zone NOT NULL DEFAULT now(),
 CONSTRAINT uk_closing_recommendation_date_stock UNIQUE(recommendation_date, stock_id),
 CONSTRAINT ck_closing_recommendation_status CHECK(status IN ('CANDIDATE','SELECTED','EXCLUDED'))
);

CREATE INDEX idx_closing_recommendation_date_rank ON closing_recommendation(recommendation_date, rank_no);
CREATE INDEX idx_closing_recommendation_score ON closing_recommendation(recommendation_score DESC);
CREATE INDEX idx_closing_recommendation_stock_date ON closing_recommendation(stock_id, recommendation_date DESC);
