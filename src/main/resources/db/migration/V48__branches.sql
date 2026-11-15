-- Branches: other places of the same business (same items and prices, shared customers, reports added up, stock moved
-- between them). A branch is a location of kind SHOP. Each staff member works at one branch: the till, stock and
-- sales follow them there. Empty = the main place (and, for owners and managers, all branches together).
ALTER TABLE users ADD COLUMN location_id uuid REFERENCES locations(id) ON DELETE SET NULL;
ALTER TABLE locations ADD COLUMN address varchar(200);
ALTER TABLE locations ADD COLUMN phone varchar(30);
ALTER TABLE locations ADD COLUMN active boolean NOT NULL DEFAULT true;
