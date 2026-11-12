-- "Remove the samples": everything the sample seeder makes is created in the signup transaction, before the owner can
-- add anything; tenants.settings.samplesUntil records that moment. These two tables had no creation time to compare.
-- Existing rows get the time of this migration, which is after any older workspace's signup, so they're never samples.
ALTER TABLE categories ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE spaces ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
