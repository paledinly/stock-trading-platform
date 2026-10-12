select run.recommendation_date, run.execution_mode, o.candidate_source, o.disposition, o.decision_reason, o.final_score,
 s.stock_code, s.is_etf, o.status, o.entry_at, o.entry_price, o.exit_at, o.exit_price, o.exit_reason,
 o.execution_ambiguous, o.gross_return_rate, o.net_return_rate, o.target_rate, o.stop_rate
from closing_candidate_observation o join closing_recommendation_run run on run.id=o.run_id join stock s on s.id=o.stock_id
order by run.recommendation_date, o.id
