-- What actually arrived on each purchase line. Empty means nobody said, which counts as all of it arrived.
ALTER TABLE purchase_lines ADD COLUMN received_qty numeric(14,3);
