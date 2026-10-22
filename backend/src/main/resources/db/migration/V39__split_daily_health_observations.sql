-- Daily completeness judges the ADR-010 primary sample (PRECISION observations) only. BROAD candidates have no
-- realtime subscription or minute backfill by design, so their gaps are kept as information, not as a defect.
-- record_stage: PRELIMINARY at close + 15m, FINAL after the 18:00 late minute backfill and re-evaluation.
-- observation_statuses keeps the combined counts (rows before V39 only have this column).
ALTER TABLE closing_daily_health ADD COLUMN precision_observation_statuses text;
ALTER TABLE closing_daily_health ADD COLUMN broad_observation_statuses text;
ALTER TABLE closing_daily_health ADD COLUMN record_stage varchar(20);
