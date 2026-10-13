-- Scanner detections in the closing window (14:00-15:30 KST) since the first v8 FORWARD date.
-- Only score/timing columns; no feature payloads.
select d.market_session_date, s.stock_code, d.scanner_type, d.detected_at, d.received_at,
       d.opportunity_score, d.risk_score, d.algorithm_version, d.score_version, d.feature_version,
       coalesce(s.is_etf, false) is_etf
from scanner_detection d join stock s on s.id = d.stock_id
where d.market_session_date >= date '2026-09-22'
  and (d.detected_at at time zone 'Asia/Seoul')::time between time '14:00' and time '15:30'
order by d.detected_at
