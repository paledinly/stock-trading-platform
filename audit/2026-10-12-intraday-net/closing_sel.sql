select r.recommendation_date, run.execution_mode, r.strategy_version, r.status rec_status, r.rank_no, r.candidate_source,
 r.recommendation_score, s.stock_code, s.is_etf, p.status perf_status, p.next_trading_date,
 p.virtual_entry_at, p.virtual_entry_price, p.execution_exit_at, p.execution_exit_price, p.execution_exit_reason,
 p.execution_ambiguous, p.gross_return_rate, p.net_return_rate, p.cost_status, p.target_rate, p.stop_rate,
 p.open_return_rate, p.close_return_rate, p.max_return_rate, p.max_drawdown_rate
from closing_recommendation r join closing_recommendation_run run on run.id=r.run_id
join stock s on s.id=r.stock_id left join overnight_performance p on p.closing_recommendation_id=r.id
order by r.recommendation_date, run.id, r.rank_no
