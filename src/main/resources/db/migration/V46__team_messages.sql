-- Reaching the LeziTech team in one tap, from every screen: questions, feedback, problems, ideas and "please call me".
-- Like setup requests, these belong to LeziTech (not to a business), so there's no row-level fence; the app only adds.
CREATE TABLE team_messages (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product       varchar(20) NOT NULL DEFAULT 'BIZNEZ',
    tenant_id     uuid REFERENCES tenants(id) ON DELETE SET NULL,
    kind          varchar(12) NOT NULL,              -- QUESTION | FEEDBACK | PROBLEM | IDEA | CALLBACK
    message       text,
    page          varchar(200),                      -- the screen they were on
    business_name varchar(200),
    contact_name  varchar(120),
    phone         varchar(30),
    email         varchar(200),
    role          varchar(20),
    status        varchar(10) NOT NULL DEFAULT 'NEW', -- NEW | SEEN | DONE
    admin_note    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    handled_at    timestamptz
);
CREATE INDEX ix_team_messages_status ON team_messages (status, created_at DESC);
GRANT SELECT, INSERT, UPDATE ON team_messages TO suite_app;
