-- ai-premarket-v5 (prompt premarket-v5.0, 2026-11-20): the AI also reports what it saw in the NXT pre-market (08:00-08:50).
-- These columns copy the AI's own NXT values out of raw_json so v5 picks can later be split by NXT evidence
-- (e.g. VERIFIED and NEAR_HIGH vs no NXT data). They are what the AI wrote, not prices the program measured.
-- Older plans (v1-v4) leave them null.

ALTER TABLE ai_premarket_plan ADD COLUMN nxt_status varchar(16);
ALTER TABLE ai_premarket_plan ADD COLUMN nxt_as_of timestamp with time zone;
ALTER TABLE ai_premarket_plan ADD COLUMN nxt_verified boolean;

ALTER TABLE ai_premarket_pick ADD COLUMN nxt_status varchar(16);
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_eligible boolean;
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_last_price numeric(19, 4);
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_change_pct numeric(12, 6);
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_turnover numeric(24, 2);
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_turnover_trend varchar(16);
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_price_retention varchar(16);
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_as_of timestamp with time zone;
ALTER TABLE ai_premarket_pick ADD COLUMN nxt_verified boolean;
