-- Sector codes from the KIS stock master (2026-11-05), for research that groups investor flow by sector. The master
-- file already carried the KRX index-sector large/medium/small codes; they were read and dropped until now. Values are
-- the master's current classification, not a point-in-time history. "0000" (unclassified) is stored as NULL.
ALTER TABLE stock ADD COLUMN sector_large_code varchar(4);
ALTER TABLE stock ADD COLUMN sector_medium_code varchar(4);
ALTER TABLE stock ADD COLUMN sector_small_code varchar(4);

-- Names for those codes, from the KIS index-code master (idxcode.mst). KOSPI codes start with 0, KOSDAQ codes with 1,
-- so a code alone is unique.
CREATE TABLE sector_code (
    code varchar(4) PRIMARY KEY,
    sector_name varchar(80) NOT NULL,
    updated_at timestamp with time zone NOT NULL
);
