-- One completeness record per trading day for the official closing pipeline.
-- Only the current KST trade date is ever written; earlier rows are left as recorded.
CREATE TABLE closing_daily_health (
    trade_date date PRIMARY KEY,
    verdict varchar(20) NOT NULL,
    reasons varchar(500) NOT NULL DEFAULT '',
    strategy_version varchar(40) NOT NULL,
    run_id bigint,
    run_completed_at timestamp with time zone,
    selected_count integer NOT NULL DEFAULT 0,
    selected_entered integer NOT NULL DEFAULT 0,
    paper_entry_statuses text NOT NULL DEFAULT '{}',
    observation_statuses text NOT NULL DEFAULT '{}',
    realtime_connected boolean,
    realtime_dropped_delta bigint,
    realtime_window_start timestamp with time zone,
    realtime_window_end timestamp with time zone,
    checked_at timestamp with time zone NOT NULL
);
