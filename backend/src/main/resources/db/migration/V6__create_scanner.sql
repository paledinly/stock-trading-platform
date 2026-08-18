CREATE TABLE scanner_setting (
 id bigserial PRIMARY KEY, owner_id bigint NOT NULL, name varchar(100) NOT NULL,
 scanner_type varchar(20) NOT NULL, min_change_rate numeric(12,6) NOT NULL DEFAULT 0,
 min_volume_ratio numeric(12,6) NOT NULL DEFAULT 0, min_5m_trading_value numeric(20,4) NOT NULL DEFAULT 0,
 min_daily_trading_value numeric(20,4) NOT NULL DEFAULT 0, min_price numeric(20,4) NOT NULL DEFAULT 0,
 include_etf boolean NOT NULL DEFAULT false, cooldown_seconds integer NOT NULL DEFAULT 300,
 is_active boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT 0,
 created_at timestamp with time zone NOT NULL DEFAULT now(), updated_at timestamp with time zone NOT NULL DEFAULT now(),
 CONSTRAINT uk_scanner_setting_owner_name UNIQUE(owner_id,name),
 CONSTRAINT ck_scanner_setting_type CHECK(scanner_type IN ('VOLUME','PRICE_RISE','MOMENTUM'))
);
CREATE TABLE scanner_detection (
 id bigserial PRIMARY KEY, event_id uuid NOT NULL UNIQUE, stock_id bigint NOT NULL REFERENCES stock(id),
 scanner_setting_id bigint NOT NULL REFERENCES scanner_setting(id), scanner_type varchar(20) NOT NULL,
 detected_at timestamp with time zone NOT NULL, detected_price numeric(20,4) NOT NULL,
 five_minute_change_rate numeric(12,6), volume_ratio numeric(12,6), current_5m_volume bigint NOT NULL,
 current_5m_trading_value numeric(20,4) NOT NULL, daily_trading_value numeric(20,4),
 momentum_score numeric(12,6), setting_snapshot text NOT NULL, source_event_id varchar(100),
 algorithm_version varchar(20) NOT NULL, market_session_date date NOT NULL
);
CREATE INDEX idx_detection_type_time ON scanner_detection(scanner_type,detected_at DESC);
CREATE INDEX idx_detection_stock_time ON scanner_detection(stock_id,detected_at DESC);
CREATE INDEX idx_detection_setting_time ON scanner_detection(scanner_setting_id,detected_at DESC);
