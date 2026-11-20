-- DEPLOY WARNING: these four indexes are built without CONCURRENTLY (Flyway runs this file in a transaction, where
-- CONCURRENTLY is not allowed). On a database that already holds sales, building them blocks every write to orders,
-- order_lines and payments, so tills stop selling mid-deploy. On such a database, BEFORE deploying, create each one
-- by hand with CREATE INDEX CONCURRENTLY IF NOT EXISTS (same names and columns as below); the IF NOT EXISTS then makes
-- this migration a no-op. On a fresh, empty database it is safe as it stands.
--
-- Phase B (a year of data): reports, the tax page and commissions pick sales by the time they were paid
-- (closed_at), which had no index, so a busy café's year report read every sale it ever made.
CREATE INDEX IF NOT EXISTS ix_orders_closed ON orders (tenant_id, closed_at) WHERE closed_at IS NOT NULL;
-- A customer's sales (statements, the customers report, "who owes you").
CREATE INDEX IF NOT EXISTS ix_orders_customer ON orders (tenant_id, customer_id, closed_at) WHERE customer_id IS NOT NULL;
-- The lines of many sales at once, by item (best sellers, profit by item, stock used).
CREATE INDEX IF NOT EXISTS ix_lines_item ON order_lines (tenant_id, item_id);
-- Payments of a sale (receipts, refunds, the till count).
CREATE INDEX IF NOT EXISTS ix_payments_order ON payments (order_id);
