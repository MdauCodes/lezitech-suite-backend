-- Refunds are negative payment rows: same method as the money that came in, booked to the
-- till session of whoever paid it back, so the end-of-day count nets them out.
ALTER TABLE payments ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'PAYMENT';   -- PAYMENT | REFUND
ALTER TABLE payments ADD COLUMN refund_reason text;
ALTER TABLE orders ADD COLUMN refunded_at timestamptz;
ALTER TABLE orders ADD COLUMN refunded_by uuid;
CREATE INDEX ix_payments_time ON payments (tenant_id, created_at);
