-- 5M bars 14:30-15:25 KST of the official-run dates, aggregated per date and bar start.
-- created/updated lag is measured from the bar end (start + 5 minutes).
select (c.start_time at time zone 'Asia/Seoul')::date d,
       to_char(c.start_time at time zone 'Asia/Seoul', 'HH24:MI') bar,
       count(*) bars, sum(case when c.is_final then 1 else 0 end) final_bars,
       count(distinct c.stock_id) stocks,
       percentile_cont(0.5) within group (order by extract(epoch from c.created_at - (c.start_time + interval '5 minutes'))) created_lag_p50,
       percentile_cont(0.5) within group (order by extract(epoch from c.updated_at - (c.start_time + interval '5 minutes'))) updated_lag_p50,
       percentile_cont(0.9) within group (order by extract(epoch from c.updated_at - (c.start_time + interval '5 minutes'))) updated_lag_p90,
       max(extract(epoch from c.updated_at - (c.start_time + interval '5 minutes'))) updated_lag_max,
       sum(case when c.updated_at <= ((c.start_time at time zone 'Asia/Seoul')::date + time '15:15') at time zone 'Asia/Seoul' then 1 else 0 end) updated_by_1515,
       sum(case when c.updated_at <= ((c.start_time at time zone 'Asia/Seoul')::date + time '15:00:10') at time zone 'Asia/Seoul' then 1 else 0 end) updated_by_150010,
       string_agg(distinct c.source, ',') sources
from stock_candle c
where c.timeframe = '5M'
  and c.start_time >= timestamptz '2026-09-22 00:00+09'
  and (c.start_time at time zone 'Asia/Seoul')::time between time '14:30' and time '15:25'
group by 1, 2 order by 1, 2
