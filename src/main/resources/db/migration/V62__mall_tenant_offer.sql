-- Phase E: the mall-tenant offer. A landlord on BizNez offers their tenants BizNez at a discount (the percent is
-- LeziTech's, set per mall in the control panel; the landlord switches it on). A tenant who signs up through the
-- mall's link keeps that discount on every payment, and the mall's landlord can get a thank-you credit (in KSh, off
-- their own next BizNez payment) for each such tenant that pays.
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS mall_id uuid REFERENCES tenants(id) ON DELETE SET NULL;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS mall_discount integer NOT NULL DEFAULT 0;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS mall_thanked_at timestamptz;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS billing_credit numeric(12, 2) NOT NULL DEFAULT 0;
-- Credit used on a payment, so it is taken off the balance only once the payment goes through.
ALTER TABLE subscription_requests ADD COLUMN IF NOT EXISTS credit_used numeric(12, 2) NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ix_tenants_mall ON tenants (mall_id) WHERE mall_id IS NOT NULL;
