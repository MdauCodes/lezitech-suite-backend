-- When the owner chose to add PayHero's charge to the customer's bill: the bill line added for this prompt,
-- so it can come off again if the prompt fails or is cancelled.
ALTER TABLE mpesa_requests ADD COLUMN fee_line_id uuid;
ALTER TABLE mpesa_requests ADD COLUMN customer_fee numeric(12,2) NOT NULL DEFAULT 0;
