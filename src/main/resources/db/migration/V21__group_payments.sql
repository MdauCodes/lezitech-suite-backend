-- Package E: one bill across several tables (a group order). Prompts and split shares for the whole group carry the
-- group's batch id; the money is spread over the group's open orders when it lands.
ALTER TABLE mpesa_requests ADD COLUMN batch_id uuid;
ALTER TABLE pay_splits ADD COLUMN batch_id uuid;
CREATE INDEX ix_pay_splits_batch ON pay_splits (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX ix_mpesa_requests_batch ON mpesa_requests (batch_id) WHERE batch_id IS NOT NULL;
