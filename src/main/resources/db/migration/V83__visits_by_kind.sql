-- V83: which kind of business a page view is about (sector key and sub-sector key), for the visits report by business type.
SET LOCAL lock_timeout = '5s';

ALTER TABLE visits ADD COLUMN IF NOT EXISTS sector text;
ALTER TABLE visits ADD COLUMN IF NOT EXISTS sub_sector text;
CREATE INDEX IF NOT EXISTS visits_sector_idx ON visits (sector, created_at);

-- Old website views of a sector page (/sectors/shops, /sectors/shops/cafe) get the sector key.
UPDATE visits SET sector = CASE split_part(path, '/', 3)
        WHEN 'restaurants' THEN 'restaurant' WHEN 'hotels' THEN 'hotel' WHEN 'shops' THEN 'retail' WHEN 'salons' THEN 'beauty'
        WHEN 'wholesale' THEN 'wholesale' WHEN 'pharmacies' THEN 'pharmacy' WHEN 'services' THEN 'services'
        WHEN 'safaris' THEN 'tours' WHEN 'property' THEN 'property' END
WHERE sector IS NULL AND site = 'website'
  AND path LIKE '/sectors/%';

-- Old views of a business's own page (/t/<slug>) get that business's sector.
UPDATE visits v SET sector = t.sector
FROM tenants t
WHERE v.sector IS NULL AND v.site = 'business-page' AND v.path LIKE '/t/%' AND t.slug = split_part(v.path, '/', 3);
