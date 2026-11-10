-- AI pre-market JSON v2 (ai-premarket-v2, 2026-11-10). v2 lets analyzedAt be null when the model could not confirm the
-- time, adds an analysis mode (PREMARKET / INTRADAY), market regime and risk level, WATCH_ONLY / DATA_INSUFFICIENT
-- decisions, and up to three top-priority stocks. The verbatim JSON stays in raw_json as before.
ALTER TABLE ai_premarket_plan ALTER COLUMN analyzed_at DROP NOT NULL;
ALTER TABLE ai_premarket_plan ADD COLUMN analysis_id varchar(80);
ALTER TABLE ai_premarket_plan ADD COLUMN analysis_mode varchar(16) NOT NULL DEFAULT 'PREMARKET';
ALTER TABLE ai_premarket_plan ADD COLUMN market_regime varchar(24);
ALTER TABLE ai_premarket_plan ADD COLUMN risk_level varchar(16);

ALTER TABLE ai_premarket_pick ADD COLUMN top_priority boolean NOT NULL DEFAULT false;
ALTER TABLE ai_premarket_pick ADD COLUMN confidence varchar(16);
