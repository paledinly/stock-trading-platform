-- Stored evaluation snapshots of official FORWARD runs (first per date), v6/v7 replays,
-- the 10-01 v8 replay that selected, and the last two LEGACY v5 runs with a snapshot.
select run.id, run.recommendation_date, run.strategy_version, run.execution_mode, run.evaluated_as_of,
       run.response_snapshot
from closing_recommendation_run run
where run.id in (1, 2, 11, 14, 15, 16, 27, 31, 37, 41, 45, 47, 49, 53, 59, 61, 62)
order by run.id
