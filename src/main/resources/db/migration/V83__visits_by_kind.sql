-- V83: which kind of business a page view is about (sector key and sub-sector key), for the visits report by business type.
SET LOCAL lock_timeout = '5s';

ALTER TABLE visits ADD COLUMN IF NOT EXISTS sector text;
ALTER TABLE visits ADD COLUMN IF NOT EXISTS sub_sector text;
CREATE INDEX IF NOT EXISTS visits_sector_idx ON visits (sector, created_at);
