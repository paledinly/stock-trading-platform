CREATE TABLE overnight_performance (
 id bigserial PRIMARY KEY,
 closing_recommendation_id bigint NOT NULL REFERENCES closing_recommendation(id),
 next_trading_date date,
 evaluated_at timestamp with time zone NOT NULL,
 open_price numeric(20,4),
 high_price numeric(20,4),
 low_price numeric(20,4),
 close_price numeric(20,4),
 open_return_rate numeric(12,6),
 close_return_rate numeric(12,6),
 max_return_rate numeric(12,6),
 max_drawdown_rate numeric(12,6),
 target_hit boolean NOT NULL DEFAULT false,
 stop_hit boolean NOT NULL DEFAULT false,
 status varchar(20) NOT NULL,
 calculation_version varchar(40) NOT NULL,
 created_at timestamp with time zone NOT NULL DEFAULT now(),
 updated_at timestamp with time zone NOT NULL DEFAULT now(),
 CONSTRAINT uk_overnight_performance_recommendation UNIQUE(closing_recommendation_id),
 CONSTRAINT ck_overnight_performance_status CHECK(status IN ('PENDING','COMPLETED','DATA_MISSING'))
);

CREATE INDEX idx_overnight_performance_status ON overnight_performance(status);
CREATE INDEX idx_overnight_performance_next_date ON overnight_performance(next_trading_date);
