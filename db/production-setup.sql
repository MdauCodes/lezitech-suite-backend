-- Run once on the production Postgres, as the database owner, AFTER the first deploy
-- (Flyway's V1 creates the suite_app role without a password).
-- The app connects as suite_app so Row-Level Security applies; Flyway connects as the owner.
ALTER ROLE suite_app WITH LOGIN PASSWORD 'replace-with-a-long-random-password';
