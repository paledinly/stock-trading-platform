select r.run_id, r.recommendation_date, r.strategy_version, r.rank_no, s.stock_code, r.recommendation_score,
       r.candidate_source, r.status
from closing_recommendation r join stock s on s.id = r.stock_id
order by r.run_id, r.rank_no
