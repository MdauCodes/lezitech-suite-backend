-- V36: paying the right bill (restaurant package R1).
-- Nobody chooses a bill from a list. The phone that ordered opens its own bill; anyone else types the bill's 4-digit
-- code (printed on the bill, or said by the waiter), which works only on that bill's table.
ALTER TABLE orders ADD COLUMN bill_code varchar(4) NOT NULL DEFAULT lpad(floor(random() * 10000)::int::text, 4, '0');

-- Every M-Pesa number that ordered onto a bill (optional when ordering), so a payment from another number is flagged.
ALTER TABLE orders ADD COLUMN ordered_phones varchar(20)[] NOT NULL DEFAULT '{}';
UPDATE orders SET ordered_phones = ARRAY[customer_phone] WHERE customer_phone IS NOT NULL;

-- A table bill paid by M-Pesa from a diner's phone waits for the waiter to look and tick "paid, checked"
-- before the table shows as free again.
ALTER TABLE orders ADD COLUMN needs_pay_check boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN pay_checked_at timestamptz;
ALTER TABLE orders ADD COLUMN pay_checked_by uuid;
CREATE INDEX ix_orders_pay_check ON orders (tenant_id, table_id) WHERE needs_pay_check AND pay_checked_at IS NULL;
