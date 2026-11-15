-- One owner, several businesses: one sign-in and a switcher.
-- A person's second business gets its own owner entry (no email or password of its own) linked by person_id to the
-- entry they sign in with; tenants that belong together share a group_id.
ALTER TABLE users ADD COLUMN person_id uuid;
UPDATE users SET person_id = id;

-- Everyone else (staff, new sign-ups) is their own person unless linked.
CREATE OR REPLACE FUNCTION users_person_default() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.person_id IS NULL THEN
        NEW.person_id := NEW.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_users_person BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION users_person_default();
ALTER TABLE users ALTER COLUMN person_id SET NOT NULL;
CREATE INDEX ix_users_person ON users (person_id);

ALTER TABLE tenants ADD COLUMN group_id uuid;
UPDATE tenants SET group_id = id;
