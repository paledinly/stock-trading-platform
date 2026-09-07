CREATE TABLE market_wide_scan_run (
 id bigserial PRIMARY KEY,
 session_date date NOT NULL,
 scheduled_for timestamp with time zone NOT NULL,
 started_at timestamp with time zone NOT NULL,
 completed_at timestamp with time zone,
 status varchar(20) NOT NULL,
 scanned_count integer NOT NULL DEFAULT 0,
 candidate_count integer NOT NULL DEFAULT 0,
 fallback boolean NOT NULL DEFAULT false,
 error_message text,
 created_at timestamp with time zone NOT NULL DEFAULT now(),
 updated_at timestamp with time zone NOT NULL DEFAULT now(),
 CONSTRAINT uk_market_wide_scan_run_schedule UNIQUE(scheduled_for),
 CONSTRAINT ck_market_wide_scan_run_status CHECK(status IN ('RUNNING','COMPLETED','FAILED'))
);

CREATE INDEX idx_market_wide_scan_run_session_time
    ON market_wide_scan_run(session_date, scheduled_for DESC);
