-- Package I: the business's own KRA receipt (ETR) is emailed through the same background jobs as receipts.
ALTER TABLE email_jobs ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'RECEIPT';   -- RECEIPT | ETR
