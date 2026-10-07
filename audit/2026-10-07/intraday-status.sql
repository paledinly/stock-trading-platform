select count(*) n,count(distinct stock_code) stocks,min(received_at) first_received,max(received_at) last_received from intraday_input where session_date=current_date;
select count(*) n, count(*) filter(where evaluated_at-received_at > interval '10 seconds') delayed, count(*) filter(where payload::jsonb->>'dataLoss'='true') data_loss from intraday_input where session_date=current_date;
