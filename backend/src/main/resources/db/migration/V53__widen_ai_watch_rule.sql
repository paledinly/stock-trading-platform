-- AI 실시간 감시 비교 규칙(기록만, 세트 x1: XM·XC·XV·XI·XL·X0)을 같은 표에 남기려고 규칙 칸을 넓힌다(2026-11-25).
-- 기존 값(P·A)은 그대로다. docs/AI_PREMARKET_PHASE2.md
ALTER TABLE ai_virtual_position ALTER COLUMN rule SET DATA TYPE varchar(10);
ALTER TABLE ai_signal_event ALTER COLUMN rule SET DATA TYPE varchar(10);
