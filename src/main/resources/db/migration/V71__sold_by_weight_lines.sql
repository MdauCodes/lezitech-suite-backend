-- Sold by weight: a line remembers it was sold in kilos ("kg"), so receipts and tickets can say so even if the item changes later.
-- A nullable column with no default: instant on a live table (no rewrite, only a brief lock). Quantities are already numeric(14,3).
ALTER TABLE order_lines ADD COLUMN unit_name text;
