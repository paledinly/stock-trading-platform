CREATE TABLE market_broad_snapshot (
 id bigserial PRIMARY KEY,
 session_date date NOT NULL,
 captured_at timestamp with time zone NOT NULL,
 stock_id bigint NOT NULL REFERENCES stock(id),
 current_price numeric(20,4),
 change_rate numeric(12,6),
 accumulated_volume bigint,
 accumulated_trading_value numeric(20,4),
 day_open numeric(20,4),
 day_high numeric(20,4),
 day_low numeric(20,4),
 trade_strength numeric(12,6),
 broad_score numeric(12,6) NOT NULL DEFAULT 0,
 ranking_sources text NOT NULL,
 data_quality varchar(20) NOT NULL,
 collection_status varchar(20) NOT NULL,
 exclusion_reason text,
 quoted_at timestamp with time zone,
 source_version varchar(40) NOT NULL,
 created_at timestamp with time zone NOT NULL DEFAULT now(),
 updated_at timestamp with time zone NOT NULL DEFAULT now(),
 CONSTRAINT uk_market_broad_snapshot_bucket_stock UNIQUE(session_date, captured_at, stock_id),
 CONSTRAINT ck_market_broad_snapshot_quality CHECK(data_quality IN ('BROAD_C','INSUFFICIENT')),
 CONSTRAINT ck_market_broad_snapshot_status CHECK(collection_status IN ('COLLECTED','QUOTE_FAILED'))
);

CREATE INDEX idx_market_broad_snapshot_session_score
    ON market_broad_snapshot(session_date, broad_score DESC);
CREATE INDEX idx_market_broad_snapshot_stock_time
    ON market_broad_snapshot(stock_id, captured_at DESC);
