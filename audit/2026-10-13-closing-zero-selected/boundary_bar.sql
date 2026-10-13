-- The bar that ends exactly at the feature freeze time: 14:55 bar for v8 (freeze 15:00, grace 10s),
-- 15:10 bar for v9 (freeze 15:15, grace 0s). The evaluator skips bars that are not final when the run
-- executes and flags lateCandleRevision for final bars whose created/updated time is after
-- freeze + grace. Counts are per date over all stocks with a stored 5M bar.
with bars as (
  select (c.start_time at time zone 'Asia/Seoul')::date d,
         to_char(c.start_time at time zone 'Asia/Seoul', 'HH24:MI') bar,
         greatest(c.created_at, c.updated_at) touched
  from stock_candle c
  where c.timeframe = '5M' and c.is_final
    and c.start_time >= timestamptz '2026-09-22 00:00+09'
    and to_char(c.start_time at time zone 'Asia/Seoul', 'HH24:MI') in ('14:55', '15:10')),
runs as (
  select recommendation_date d, min(generated_at) run_at from closing_recommendation_run
  where execution_mode = 'FORWARD' group by recommendation_date)
select b.d, b.bar, count(*) bars,
       r.run_at official_run_at,
       sum(case when b.bar = '14:55' and b.touched <= (b.d + time '15:00:10') at time zone 'Asia/Seoul' then 1
                when b.bar = '15:10' and b.touched <= (b.d + time '15:15:00') at time zone 'Asia/Seoul' then 1 else 0 end) available_at_cutoff,
       sum(case when b.bar = '15:10' and b.touched > (b.d + time '15:15:00') at time zone 'Asia/Seoul'
                 and b.touched <= (b.d + time '15:15:15') at time zone 'Asia/Seoul' then 1 else 0 end) v9_flag_if_run_151515,
       sum(case when b.bar = '15:10' and b.touched > (b.d + time '15:15:00') at time zone 'Asia/Seoul'
                 and b.touched <= (b.d + time '15:15:30') at time zone 'Asia/Seoul' then 1 else 0 end) v9_flag_if_run_151530,
       sum(case when b.touched > (b.d + case when b.bar = '14:55' then time '15:00:10' else time '15:15:00' end) at time zone 'Asia/Seoul'
                 and b.touched <= r.run_at then 1 else 0 end) flagged_at_actual_run
from bars b left join runs r on r.d = b.d
group by b.d, b.bar, r.run_at order by b.d, b.bar
