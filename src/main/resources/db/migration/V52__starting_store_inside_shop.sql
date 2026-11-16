-- A business's starting store (the restaurant's kitchen store, a shop's back store...) belongs inside its shop, not
-- beside it as a second branch: plans count branches, and a one-shop business must fit the smallest plan.
-- Only the stores sign-up made, and only where the business has exactly one top-level shop.
UPDATE locations st SET parent_id = (
    SELECT sh.id FROM locations sh WHERE sh.tenant_id = st.tenant_id AND sh.kind = 'SHOP' AND sh.parent_id IS NULL)
WHERE st.kind = 'STORE' AND st.parent_id IS NULL
  AND st.name IN ('Kitchen store', 'Main store', 'Back store', 'Dispensary store')
  AND (SELECT count(*) FROM locations sh WHERE sh.tenant_id = st.tenant_id AND sh.kind = 'SHOP' AND sh.parent_id IS NULL) = 1;
