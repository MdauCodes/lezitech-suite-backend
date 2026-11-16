-- Staff job titles the business chooses (Director, Operations manager, Store keeper...), each working like one of the
-- built-in access levels (users.role). owner_powers: the owner lets this person do owner things too
-- (plan, payments, who can do what, business details).
ALTER TABLE users ADD COLUMN job_title varchar(60);
ALTER TABLE users ADD COLUMN owner_powers boolean NOT NULL DEFAULT false;
