-- Reference indicators recorded next to the daily completeness verdict (they do not change the verdict):
-- precision replacements of the day, precision candidates flagged for stale/missing daily bars at 15:15,
-- and intraday realtime coverage gaps with bars still missing. Stored as a JSON object.
ALTER TABLE closing_daily_health ADD COLUMN indicators text;
