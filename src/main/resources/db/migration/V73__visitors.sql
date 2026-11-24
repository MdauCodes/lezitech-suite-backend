-- V73: where visitors come from. One small row per page view on the website and the app's public pages (no cookies,
-- no address, no browser text kept), and the source a business first came from on its sign-up.
CREATE TABLE visits (
    id            bigserial PRIMARY KEY,
    created_at    timestamptz NOT NULL DEFAULT now(),
    site          varchar(16) NOT NULL,
    path          varchar(200),
    referrer_host varchar(100),
    utm_source    varchar(100),
    utm_medium    varchar(100),
    utm_campaign  varchar(100),
    has_gclid     boolean NOT NULL DEFAULT false,
    has_fbclid    boolean NOT NULL DEFAULT false,
    device        varchar(10) NOT NULL,
    visitor_id    varchar(40),
    first_visit   boolean NOT NULL DEFAULT false,
    source        varchar(24) NOT NULL,
    source_detail varchar(100)
);
CREATE INDEX visits_created_idx ON visits (created_at);
CREATE INDEX visits_source_idx ON visits (source, created_at);

ALTER TABLE tenants ADD COLUMN signup_source jsonb;

-- The app signs in as suite_app, which needs to be told about each new table.
GRANT SELECT, INSERT, DELETE ON visits TO suite_app;
GRANT USAGE ON SEQUENCE visits_id_seq TO suite_app;
