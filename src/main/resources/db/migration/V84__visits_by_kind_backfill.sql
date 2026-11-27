-- V84: fill in the business type on visits recorded before V83. Kept apart from V83 so the column change
-- holds its lock only for an instant; these updates lock rows, not the table, so new visits keep arriving.
SET LOCAL lock_timeout = '5s';

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
