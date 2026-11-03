# Lezitech Suite

Multi-tenant POS + CRM + ERP sold as **Lezitech free 7-day trials**. One product; each business picks a
**sector pack** at signup and gets screens, vocabulary, till layout, defaults and sample data made for it.

| Repo | Path | Stack |
|---|---|---|
| API | `J:\backend\lezitech-suite-api` | Spring Boot 3.3, Java 21, JDBC, Flyway, PostgreSQL with Row-Level Security |
| Web | `J:\frontend\lezitech-suite-web` | Vite, React 18, TypeScript, Tailwind 4, TanStack Query |

## Sector packs (`src/main/resources/sectors.json`)

Modelled on ERPNext's `erpnext/domains/*.py` (per-industry desktop icons + settings) and Odoo's per-industry
apps. Adding a sector = adding a JSON entry; no code change.

| Pack | Opens on | What's different |
|---|---|---|
| Restaurants & cafés | Floor plan | Tables with live bills and timers, add-ons (sides, cooking, extras), courses, send to kitchen/bar, kitchen display by station, split bill by item, tips, takeaway/delivery |
| Shops & minimarts | Till | Scan-first barcode till, low-stock flags, store-to-shop transfers, credit customers, loyalty |
| Salons, barbers & beauty | Bookings | Diary with a column per stylist, double-booking guard, check-out turns a booking into a ticket, sales credited per stylist (commissions) |
| Wholesale & distribution | Invoice | Dense list till, quantity price tiers, quotations → invoices, sell on account with credit limits, warehouse feeds shop |
| Pharmacies & chemists | Till | Batches with first-expiry-first-out, expiry report, prescription-only warning at the till |
| Service businesses | Pipeline | CRM-first: kanban with stale-deal highlighting, follow-up reminders, won deal → customer, quotations, invoices |

Every pack shares: M-Pesa/cash/card/on-account split tender with change, till open/close with per-method and
per-cashier reconciliation (cf. Odoo `pos_hr`), receipts with logo + KRA PIN + WhatsApp share, customers,
stock counts and movements, purchasing with "reorder low stock", reports (VAT, gross profit, by item / staff /
method / category / channel, CSV), team roles + till PINs, branding (Odoo self-order theming technique),
spreadsheet import (paste from Excel or CSV) for items and customers.

The owner can switch any screen or feature on/off in Settings and reset to the pack defaults.

## Trials and billing

- Signup creates the workspace, the owner login and the pack's sample data in one step; `trial_ends_at = now + TRIAL_DAYS` (7).
- When the trial (or a paid period) ends, `TrialGuard` answers every write with **402 TRIAL_EXPIRED**; reads keep working so nobody loses data. The app shows a banner and the plans page.
- Plans (KES/month): Lite 2,000 · Growth 4,500 · Pro 9,000; yearly = 10 × monthly. "Choose plan" records a `subscription_requests` row; **no money moves through the app yet**.
- Operator console API (header `X-Operator-Key: $OPERATOR_KEY`):
  `GET /api/platform/tenants`, `POST /api/platform/tenants/{id}/activate {plan, months}`,
  `POST .../extend-trial {days}`, `POST .../suspend`.

## Tenancy and security

- Every business table has `tenant_id` and an RLS policy on `app.tenant`; `TenantTransactionManager` sets it
  transaction-locally at the start of each transaction, so pooled connections can't leak. Unset = zero rows.
- The app connects as `suite_app` (non-owner, so RLS applies); Flyway migrates as the owner.
- `tenants`, `users`, `subscription_requests` have no RLS (login happens before a tenant is known); every query
  on them filters by the caller's tenant in code.
- Verified locally: a token from one workspace gets 404 for another workspace's order.

## Run locally

No Docker or Postgres password needed: the API starts an embedded Postgres 14 on port 54329 with data in `.localdb/`.

```bash
cd J:/backend/lezitech-suite-api && ./mvnw spring-boot:run      # http://localhost:8090
cd J:/frontend/lezitech-suite-web && npm run dev                # http://localhost:5320 (proxies /api)
```

Or start `suite-api` and `suite-web` from the preview launcher.

## Deploy (separate Contabo VPS + Coolify, per the 29 Oct plan)

1. Postgres service. API env: `EMBEDDED_DB=false`, `DATABASE_URL=jdbc:postgresql://db:5432/suite`,
   `DATABASE_USERNAME=suite_app`, `DATABASE_PASSWORD=…`, `FLYWAY_USERNAME=<owner>`, `FLYWAY_PASSWORD=…`,
   `JWT_SECRET` (64+ random chars), `OPERATOR_KEY`, `CORS_ORIGINS=https://app.<domain>`.
2. First deploy runs the migrations; then run `db/production-setup.sql` once to give `suite_app` its password.
3. Web: build arg `VITE_API_URL=https://api.<domain>`; nginx serves the SPA.
4. Marketing: `https://app.<domain>/for/restaurant` (or retail, beauty, wholesale, pharmacy, services) is the
   ad landing page for one sector; it pre-selects the pack at signup.

## Not built yet (next steps, roughly in order)

1. **M-Pesa**: STK push at the till and Daraja C2B confirmation; automatic subscription payment and activation.
2. **KRA eTIMS** invoice signing (the biggest compliance gap for Kenyan shops).
3. Cashier switch by PIN in the till UI (the API endpoint `POST /api/auth/pin` exists).
4. Offline till (queue sales in IndexedDB, sync later).
5. Operator console UI (only the API exists), trial-ending emails/WhatsApp nudges on day 5 and 7.
6. Plan limits enforcement (users/locations per plan), per-location prices, shop-requests-stock-from-store flow.
7. Accounting ledger (journal postings, P&L, balance sheet) — Moments Core already has this to port.
8. Mall / landlord product (rent, service charge, occupancy) as a separate module, per the 29 Oct notes.
9. Automated tests (the RLS isolation test from DukaFlow should be ported first) and `.xlsx` import.

## Local demo workspaces

Created on the embedded database in `.localdb/` (local only; they don't exist anywhere else).

| Workspace | Sector | Email | Password |
|---|---|---|---|
| Savanna Grill & Rooms | Restaurant with rooms | savanna@example.test | demo12345 |
| Demo hotel | Hotels & lodges | owner-hotel@example.test | password123 |
| Demo tours | Safaris & tours | owner-tours@example.test | password123 |
| Demo property | Malls & property | owner-property@example.test | password123 |
| Test restaurant / retail / beauty / wholesale / pharmacy / services | one each | owner-<sector>@example.test | password123 |

Customer screens for Savanna Grill: `/menu/savanna-grill-rooms`, `/w/savanna-grill-rooms`, and the order board
link from Settings → Customer screens & links.
