-- R8: the owner's night summary and theft alerts.

-- Each day's summary, kept so the owner can open it again and download it as a PDF.
CREATE TABLE night_summaries (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    day         date NOT NULL,
    data        jsonb NOT NULL,
    reason      varchar(20) NOT NULL,            -- ALL_TILLS_CLOSED | LATEST_TIME | ON_REQUEST
    emailed_to  varchar(400),
    sent_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, day)
);

ALTER TABLE night_summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE night_summaries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON night_summaries
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON night_summaries TO suite_app;

-- Bills printed for the customer: changes after printing are a warning sign.
ALTER TABLE orders ADD COLUMN bill_printed_at timestamptz;
ALTER TABLE orders ADD COLUMN bill_prints integer NOT NULL DEFAULT 0;
