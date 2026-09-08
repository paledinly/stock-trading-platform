ALTER TABLE closing_recommendation ALTER COLUMN source_detection_id DROP NOT NULL;
ALTER TABLE closing_recommendation ALTER COLUMN scanner_type DROP NOT NULL;

ALTER TABLE closing_recommendation ADD COLUMN candidate_source varchar(20) NOT NULL DEFAULT 'PRECISION';
ALTER TABLE closing_recommendation ADD COLUMN data_quality varchar(20) NOT NULL DEFAULT 'PRECISION_B';
ALTER TABLE closing_recommendation ADD COLUMN coverage_minutes integer NOT NULL DEFAULT 0;
ALTER TABLE closing_recommendation ADD COLUMN broad_snapshot_id bigint REFERENCES market_broad_snapshot(id);
ALTER TABLE closing_recommendation ADD COLUMN candidate_observed_at timestamp with time zone;
ALTER TABLE closing_recommendation ADD COLUMN missing_features text NOT NULL DEFAULT '[]';

UPDATE closing_recommendation recommendation
SET candidate_observed_at = detection.detected_at
FROM scanner_detection detection
WHERE recommendation.source_detection_id = detection.id;

ALTER TABLE closing_recommendation ALTER COLUMN candidate_observed_at SET NOT NULL;

ALTER TABLE closing_recommendation ADD CONSTRAINT ck_closing_recommendation_candidate_source
    CHECK(candidate_source IN ('PRECISION','BROAD'));
ALTER TABLE closing_recommendation ADD CONSTRAINT ck_closing_recommendation_coverage_minutes
    CHECK(coverage_minutes >= 0);

CREATE INDEX idx_closing_recommendation_source_date
    ON closing_recommendation(candidate_source, recommendation_date);
CREATE INDEX idx_closing_recommendation_broad_snapshot
    ON closing_recommendation(broad_snapshot_id);
