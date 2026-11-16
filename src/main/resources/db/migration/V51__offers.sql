-- Offers LeziTech sends to businesses from the control panel:
--   PERCENT: percent off monthly payments, for up to `months` payments per business, until ends_at
--   PRICE:   a set monthly price for one plan, until ends_at
--   DAYS:    extra free days, added to every matching free trial when the offer is made
CREATE TABLE platform_offers (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        varchar(10) NOT NULL CHECK (kind IN ('PERCENT', 'PRICE', 'DAYS')),
    title       varchar(80) NOT NULL,
    message     varchar(300),
    percent     int CHECK (percent BETWEEN 1 AND 90),
    months      int CHECK (months BETWEEN 1 AND 24),
    days        int CHECK (days BETWEEN 1 AND 60),
    plan        varchar(12),
    price       int CHECK (price >= 0),
    audience    varchar(10) NOT NULL DEFAULT 'ALL' CHECK (audience IN ('ALL', 'TRIAL', 'PAYING')),
    sector      varchar(30),
    ends_at     timestamptz NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    reached     int NOT NULL DEFAULT 0,
    created_by  varchar(120),
    created_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE subscription_requests ADD COLUMN offer_id uuid REFERENCES platform_offers (id);

GRANT SELECT, INSERT, UPDATE ON platform_offers TO suite_app;
