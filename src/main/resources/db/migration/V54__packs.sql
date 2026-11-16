-- Buy by the carton, sell by the piece. A product's pack reuses the buying-unit fields kitchen ingredients have:
-- buy_unit = the pack's name (carton, crate, bale, dozen), buy_size = pieces in one pack, buy_price = buying price of a pack.
-- pack_price: the selling price of a whole pack (empty: pieces × the piece price).
ALTER TABLE items ADD COLUMN pack_price numeric(14,2);
-- A sale line for a whole pack: how many pieces one of it is (null: a piece). Stock and cost use qty × pack_size.
ALTER TABLE order_lines ADD COLUMN pack_size numeric(14,3);
-- An order to a supplier counted in packs (stock goes up by packs × pieces in a pack).
ALTER TABLE purchase_lines ADD COLUMN in_packs boolean NOT NULL DEFAULT false;
