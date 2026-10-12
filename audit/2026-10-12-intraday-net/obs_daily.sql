with o as (select run.recommendation_date, o.disposition, o.decision_reason, o.final_score, o.stock_id, s.stock_code, s.is_etf,
  o.signal_price, o.expected_session_date,
  case when o.expected_session_date='2026-10-05' then date '2026-10-06' else o.expected_session_date end actual_next
  from closing_candidate_observation o join closing_recommendation_run run on run.id=o.run_id join stock s on s.id=o.stock_id)
select o.recommendation_date, o.disposition, o.decision_reason, o.final_score, o.stock_code, o.is_etf, o.signal_price,
 o.expected_session_date, o.actual_next, d.open, d.high, d.low, d.close
from o left join stock_candle d on d.stock_id=o.stock_id and d.timeframe='1D'
 and (d.start_time at time zone 'Asia/Seoul')::date=o.actual_next
