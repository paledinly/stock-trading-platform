-- Whole-day scanner detection counts per session date (to see if the scanner ran at all).
select d.market_session_date, count(*) n, min(d.detected_at) first_at, max(d.detected_at) last_at,
       count(distinct d.stock_id) stocks,
       sum(case when d.opportunity_score >= 35 and d.risk_score <= 65 then 1 else 0 end) passing_opp_risk
from scanner_detection d
where d.market_session_date >= date '2026-09-15'
group by d.market_session_date order by 1
