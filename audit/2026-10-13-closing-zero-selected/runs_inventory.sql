select run.id, run.recommendation_date, run.strategy_version, run.execution_mode, run.generated_at,
       run.evaluated_as_of, run.completed_at, run.request_key, length(run.response_snapshot) snapshot_len,
       run.settings_snapshot,
       (select count(*) from closing_recommendation r where r.run_id = run.id) selected_rows
from closing_recommendation_run run
order by run.id
