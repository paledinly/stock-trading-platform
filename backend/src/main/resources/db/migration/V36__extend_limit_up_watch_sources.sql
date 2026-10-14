-- 2026-10-14 the KIS fluctuation ranking alone missed a stock at +29.93% that the market-wide snapshots had.
-- candidate_source tells which source put the stock in the sample; ranking_rows records how many rows the
-- ranking returned per market, so its coverage can be checked later.
ALTER TABLE limit_up_watch ADD COLUMN candidate_source varchar(20);
ALTER TABLE limit_up_watch ADD COLUMN ranking_rows varchar(40);
