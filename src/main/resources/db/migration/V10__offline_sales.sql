-- Sales rung up while the till had no internet, sent later. They keep the time they really happened.
ALTER TABLE orders ADD COLUMN offline boolean NOT NULL DEFAULT false;
