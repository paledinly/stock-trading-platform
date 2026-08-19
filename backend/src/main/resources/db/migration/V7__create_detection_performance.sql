CREATE TABLE detection_performance (
 detection_id bigint PRIMARY KEY REFERENCES scanner_detection(id) ON DELETE CASCADE,
 price_5m numeric(20,4), price_10m numeric(20,4), price_30m numeric(20,4), price_60m numeric(20,4),
 close_price numeric(20,4), highest_price numeric(20,4), lowest_price numeric(20,4),
 return_5m numeric(12,6), return_10m numeric(12,6), return_30m numeric(12,6),
 return_60m numeric(12,6), return_close numeric(12,6), max_return numeric(12,6), max_drawdown numeric(12,6),
 status varchar(20) NOT NULL DEFAULT 'PENDING', calculation_version varchar(20) NOT NULL,
 observed_5m_at timestamp with time zone, observed_10m_at timestamp with time zone,
 observed_30m_at timestamp with time zone, observed_60m_at timestamp with time zone,
 close_observed_at timestamp with time zone, finalized_at timestamp with time zone,
 updated_at timestamp with time zone NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0,
 CONSTRAINT ck_performance_status CHECK(status IN ('PENDING','COMPLETED','DATA_MISSING'))
);
CREATE INDEX idx_performance_status ON detection_performance(status);
