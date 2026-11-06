-- Reminders before a free trial ends: day 5 ("2 days left") and day 7 ("ends today").
-- Sent by email when an email service is set up; a ready-made WhatsApp message is always
-- prepared too, for the Lezitech team to send by hand from the control panel.
CREATE TABLE trial_nudges (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind          varchar(10) NOT NULL,            -- DAY5 | DAY7
    email_to      varchar(200),
    email_status  varchar(12) NOT NULL,            -- SENT | FAILED | OFF | NO_EMAIL | SKIPPED
    email_error   text,
    whatsapp_to   varchar(30),
    whatsapp_text text,
    whatsapp_done_at timestamptz,
    whatsapp_done_by varchar(200),
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, kind)
);
GRANT SELECT, INSERT, UPDATE ON trial_nudges TO suite_app;
