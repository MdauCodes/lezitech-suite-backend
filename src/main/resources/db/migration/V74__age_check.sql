-- Age check for liquor stores and bars. An item can be marked "18+ only"; a bill with such an item must have the
-- customer's age confirmed by a staff member before it can be paid. The confirmation (who, when, ID looked at)
-- is kept on the bill. Nullable or defaulted columns only: instant on live tables.
ALTER TABLE items ADD COLUMN age_restricted boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN age_confirmed_at timestamptz;
ALTER TABLE orders ADD COLUMN age_confirmed_by uuid;
ALTER TABLE orders ADD COLUMN age_id_checked boolean;
-- Line times come from the same clock as the age confirmation (clock_timestamp), so a line added while someone confirms is never missed.
ALTER TABLE order_lines ALTER COLUMN created_at SET DEFAULT clock_timestamp();
