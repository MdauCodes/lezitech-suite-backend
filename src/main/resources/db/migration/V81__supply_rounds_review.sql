-- V81: supply rounds review. Who set the address the confirm email goes to, and a stop the office closed without a confirmation.
ALTER TABLE delivery_stops ADD COLUMN email_set_by varchar(120);
ALTER TABLE delivery_stops DROP CONSTRAINT delivery_stops_status_check;
ALTER TABLE delivery_stops ADD CONSTRAINT delivery_stops_status_check
    CHECK (status IN ('PENDING', 'EMAILED', 'CONFIRMED', 'PART', 'MISSED'));
