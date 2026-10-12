with b as (select * from (values ('2026-10-12'::date,6655893,8054658)) v(d,lo,hi)),
x as (select b.d session_date, i.received_at, i.evaluated_at,
  (substring(i.payload from '"occurredAt":"([^"]+)"'))::timestamptz occ
 from b cross join lateral generate_series(b.lo, b.hi, 200) g join intraday_input i on i.id=g)
select session_date, extract(hour from received_at at time zone 'Asia/Seoul')::int hr, count(*) n,
 sum((occ>received_at)::int) occ_after_rcv,
 sum((received_at>evaluated_at)::int) rcv_after_eval,
 round(percentile_cont(0.5) within group (order by extract(epoch from received_at-occ))::numeric,3) med_lag,
 round(percentile_cont(0.05) within group (order by extract(epoch from received_at-occ))::numeric,3) p05_lag,
 round(min(extract(epoch from received_at-occ))::numeric,3) min_lag,
 round(percentile_cont(0.99) within group (order by extract(epoch from received_at-occ))::numeric,3) p99_lag
from x group by 1,2 order by 1,2
