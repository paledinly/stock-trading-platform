select source,count(*) n,count(*) filter(where (start_time at time zone 'Asia/Seoul')::time=time '15:30') closing_bucket from stock_candle where timeframe='5M' group by 1;
select count(distinct stock_id) detection_stocks from scanner_detection;
select count(*) n,count(*) filter(where feature_snapshot::jsonb->>'tradingHalted'='true') halt_feature from closing_recommendation;
select version,description,success from flyway_schema_history order by installed_rank desc limit 3;
select count(*) n, count(*) filter(where (r.candidate_observed_at at time zone 'Asia/Seoul')::time>time '15:00') after_cutoff,count(*) filter(where r.generated_at>(r.recommendation_date+time '15:20') at time zone 'Asia/Seoul') generated_after_deadline from closing_recommendation r join overnight_performance p on p.closing_recommendation_id=r.id where p.status='COMPLETED';
