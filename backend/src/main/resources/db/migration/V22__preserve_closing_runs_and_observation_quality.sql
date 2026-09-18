ALTER TABLE closing_recommendation_run ADD COLUMN execution_mode varchar(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE closing_recommendation_run ADD COLUMN evaluated_as_of timestamp with time zone;
ALTER TABLE closing_recommendation_run ADD COLUMN completed_at timestamp with time zone;
ALTER TABLE closing_recommendation_run ADD COLUMN settings_snapshot text NOT NULL DEFAULT '{}';
ALTER TABLE closing_recommendation_run ADD COLUMN settings_hash varchar(64);
ALTER TABLE closing_recommendation_run ADD COLUMN data_version varchar(40) NOT NULL DEFAULT 'legacy-unversioned';
ALTER TABLE closing_recommendation_run ADD COLUMN request_key varchar(100);
ALTER TABLE closing_recommendation_run ADD CONSTRAINT uk_closing_run_request_key UNIQUE (request_key);

ALTER TABLE closing_recommendation ADD COLUMN run_id bigint REFERENCES closing_recommendation_run(id);

-- Historical audit rows may describe candidates that were subsequently replaced.
-- Give surviving recommendations their own legacy run without changing their IDs.
INSERT INTO closing_recommendation_run
 (recommendation_date, generated_at, strategy_version, response_snapshot, completed_at, request_key)
SELECT recommendation_date, max(generated_at), max(strategy_version), '{}', max(generated_at),
       'legacy-migration-' || cast(recommendation_date as varchar)
FROM closing_recommendation GROUP BY recommendation_date;

UPDATE closing_recommendation r SET run_id = (
    SELECT a.id FROM closing_recommendation_run a
    WHERE a.request_key = 'legacy-migration-' || cast(r.recommendation_date as varchar)
);

ALTER TABLE closing_recommendation ALTER COLUMN run_id SET NOT NULL;
ALTER TABLE closing_recommendation DROP CONSTRAINT uk_closing_recommendation_date_stock;
ALTER TABLE closing_recommendation ADD CONSTRAINT uk_closing_recommendation_run_stock UNIQUE(run_id, stock_id);
CREATE INDEX idx_closing_recommendation_run_rank ON closing_recommendation(run_id, rank_no);

ALTER TABLE overnight_performance ADD COLUMN expected_session_date date;
ALTER TABLE overnight_performance ADD COLUMN session_open timestamp with time zone;
ALTER TABLE overnight_performance ADD COLUMN session_close timestamp with time zone;
ALTER TABLE overnight_performance ADD COLUMN observed_through timestamp with time zone;
ALTER TABLE overnight_performance ADD COLUMN missing_intervals text NOT NULL DEFAULT '[]';
ALTER TABLE overnight_performance ADD COLUMN latest_price numeric(20,4);
ALTER TABLE overnight_performance ADD COLUMN latest_return_rate numeric(12,6);
ALTER TABLE overnight_performance ADD COLUMN target_rate numeric(12,6);
ALTER TABLE overnight_performance ADD COLUMN stop_rate numeric(12,6);
ALTER TABLE overnight_performance DROP CONSTRAINT ck_overnight_performance_status;
ALTER TABLE overnight_performance ADD CONSTRAINT ck_overnight_performance_status
 CHECK(status IN ('PENDING','IN_PROGRESS','COMPLETED','DATA_MISSING','DATA_INCOMPLETE'));
