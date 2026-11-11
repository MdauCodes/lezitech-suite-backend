-- V20: diners pay from their phone. A prompt can carry a tip on top of the bill, and can be one share of a split.
ALTER TABLE mpesa_requests ADD COLUMN tip      numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE mpesa_requests ADD COLUMN split_id uuid;
ALTER TABLE pay_splits ADD COLUMN tip numeric(14,2) NOT NULL DEFAULT 0;
