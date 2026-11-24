-- ai_premarket_plan.trade_decision was varchar(16) (V47), but v2 added DATA_INSUFFICIENT (17 characters), so a plan
-- with that decision failed to save with "value too long" (2026-11-24). ai_watch_session already uses varchar(20).
ALTER TABLE ai_premarket_plan ALTER COLUMN trade_decision TYPE varchar(24);
