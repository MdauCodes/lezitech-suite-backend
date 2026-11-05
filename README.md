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
   The server **refuses to start** on a real database if `JWT_SECRET` is missing, shorter than 64 characters or the
   development default, or if `OPERATOR_KEY` is missing, shorter than 24 characters or the default. Generate them with
   `openssl rand -base64 64` and `openssl rand -hex 24`.
2. First deploy runs the migrations; then run `db/production-setup.sql` once to give `suite_app` its password.
3. Web: build arg `VITE_API_URL=https://api.<domain>`; nginx serves the SPA.
4. Marketing: `https://app.<domain>/for/restaurant` (or retail, beauty, wholesale, pharmacy, services) is the
   ad landing page for one sector; it pre-selects the pack at signup.

## M-Pesa (Safaricom Daraja)

Off unless `MPESA_MODE` is set. Keys come only from environment variables; nothing is stored in the code.

| Variable | What it is |
|---|---|
| `MPESA_MODE` | `off` (default), `sandbox`, `production`, or `simulate` (local practice: no money moves, refused on a real database) |
| `MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET` | From the Daraja app on developer.safaricom.co.ke |
| `MPESA_SHORTCODE`, `MPESA_PASSKEY` | Till/paybill and its Lipa na M-Pesa passkey (sandbox: 174379 and the test passkey from the Daraja portal) |
| `MPESA_CALLBACK_BASE` | Public https address of this API, e.g. `https://api.lezitech.co.ke`; Safaricom posts results there |
| `MPESA_SUBSCRIPTION_SHORTCODE` | Lezitech's own paybill for plan payments (defaults to `MPESA_SHORTCODE`) |
| `MPESA_C2B_SECRET` | Long random string; part of the C2B URLs so nobody else can post fake payments |
| `MPESA_BASE_URL` | `https://sandbox.safaricom.co.ke` (default) or `https://api.safaricom.co.ke` |

- **Till**: choose M-Pesa, type the customer's number, "Send prompt". The sale is marked paid only after Safaricom's
  callback arrives, matches the request we sent (secret URL + checkout id) and the amount. Repeated callbacks are
  recorded once. If the sale was closed meanwhile, the money is kept as "unapplied" for staff to refund or use.
- **Direct till payments (C2B)**: register `MPESA_CALLBACK_BASE/api/public/mpesa/c2b/<MPESA_C2B_SECRET>/confirmation`
  (and `/validation`) with Daraja. A workspace claims its till by setting `mpesaTill` in its settings. If the customer
  typed the sale number as the account, the sale is paid automatically; otherwise it waits in
  `GET /api/mpesa/till-payments` for staff to put on a sale.
- **Plans**: Billing → "Pay now with M-Pesa" switches the plan on when Safaricom confirms. The manual request
  ("Pay another way") and the control panel's billing queue still work as the fallback.
- Locally, `.claude/launch.json` runs the API with `MPESA_MODE=simulate`; a phone ending in 9999 acts as a
  customer who cancels.
- Go-live still needs: each shop's own till number and passkey (today one platform shortcode is used for all
  workspaces), Safaricom production approval, and registering the C2B URLs per till.

## KRA eTIMS

Every completed sale of a workspace that has a KRA PIN in Settings is sent for a signed invoice; every refund gets
a credit note. The receipt prints the KRA invoice number, control-unit id, signature and QR code. If KRA can't be
reached the sale still completes and the invoice waits in `etims_queue`, retried every minute (backing off to hourly).

The till only talks to the `EtimsClient` interface (`src/main/java/.../etims`). Today the only client is a **stub**
(`ETIMS_MODE=stub`) that returns numbers in the same shape, marked "TEST ONLY · not valid for tax"; the default
`ETIMS_MODE=off` sends nothing. There are no public eTIMS sandbox keys, so the real client isn't written yet.

To go live with OSCU (online, for most shops) or VSCU (high volume):
1. Each shop's KRA PIN must be registered for eTIMS on the KRA iTax portal and the integrator (Lezitech) approved
   as a third-party integrator, which gets access to the eTIMS sandbox and its test PINs.
2. Initialise each device/branch with KRA (the eTIMS device initialisation call) to get the control-unit id and key; store them per
   workspace/branch (encrypted).
3. Write `OscuEtimsClient implements EtimsClient` mapping our invoice to KRA's sales call (item classification codes,
   tax bands A–E: today 16% → B, 8% → E, 0% → C), plus item and stock-movement reporting that KRA also expects.
4. Pass KRA's sandbox certification tests, then switch `ETIMS_MODE` to the real client.

## Not built yet (next steps, roughly in order)

1. **KRA eTIMS real client** (OSCU/VSCU) — waiting on KRA sandbox access; see above.
2. Cashier switch by PIN in the till UI (the API endpoint `POST /api/auth/pin` exists).
3. Offline till (queue sales in IndexedDB, sync later).
4. Trial-ending emails/WhatsApp nudges on day 5 and 7.
5. Plan limits enforcement (users/locations per plan), per-location prices, shop-requests-stock-from-store flow.
6. Accounting ledger (journal postings, P&L, balance sheet).
7. Mall / landlord product (rent, service charge, occupancy) as a separate module, per the 29 Oct notes.
8. `.xlsx` import; a screen for unmatched direct M-Pesa till payments (the API exists).

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

## Piloting: one-click demo logins

`/demo` lists one ready-made business per sector with a button per role (owner, manager, waiter, chef, cashier,
front desk, stylist, sales rep, rent collector…). Clicking signs you in as that person; each role only sees the
screens for its job and lands on the right one (waiter → floor plan, chef → kitchen screen, front desk → rooms).
Inside a demo a black bar lets you switch person in one click. Demo businesses (slugs `demo-<sector>`) are
created at startup, never expire, are excluded from control-panel numbers, and can be reset from the panel.
Turn them off with `DEMO_LOGINS=false`.

## Lezitech control panel (`/admin`)

For the Lezitech team only; separate login and token (a workspace token is refused by `/api/admin/**` and an
admin token is refused by workspace endpoints).

- **Overview:** MRR, trials, trial→paid conversion, signups per day, by sector, trials ending (who to call), latest team actions.
- **Workspaces:** search and filter every business; detail page with usage (orders, sales, own items added,
  activity chart), people and last sign-ins, team notes, links to their staff login/menu/order board.
  Actions: activate a plan, extend a trial, suspend/reactivate, switch screens and features for that workspace,
  and **open their portal as any of their roles** to see exactly what they see.
- **Billing queue:** owners who chose a plan; confirm the M-Pesa code and activate.
- **Sectors & packs:** pause or reopen signups per sector.
- **Demo businesses:** reset any demo to fresh data.
- **Lezitech team:** add teammates as superadmin, admin, support, sales or developer (permissions per role).
- **Audit log:** every control-panel action, who did it and to which workspace.
- **System health:** uptime, memory, database size, migrations, row counts.

First login on a real server: set `ADMIN_EMAIL` and `ADMIN_PASSWORD`; that superadmin is created at startup.
Locally the sign-in page also shows one-click buttons per staff role; these only exist when the API runs on the
embedded database and can never be switched on against a real `DATABASE_URL`.

## Who can do what (enforced in the API)

`config/RoleGuard.java` checks every workspace request against the caller's job, matching what the app
shows each role (`lib/roles.ts` in the web app). The owner can do everything. Anything not listed is open to
any signed-in staff member.

| Area | Allowed (besides the owner) |
|---|---|
| Staff list and changes, plan and billing | Manager |
| Settings changes | Owner only |
| Sales reports | Manager, Accounts |
| Home numbers | Manager, Sales, Accounts |
| Changing items, categories, places, tables, suppliers | Manager |
| Stock and buying (read and change) | Manager, Accounts (changes: manager, checked again in the service) |
| Kitchen screen updates | Manager, Kitchen, Waiter, Front desk, Cashier |
| Taking orders and payments | Everyone except Kitchen |
| Opening/closing the till | Manager, Cashier, Sales, Front desk, Accounts |
| Customers (change) | Everyone except Kitchen; recording debt payments: Manager, Cashier, Sales, Front desk, Accounts |
| Sales leads | Manager, Sales |
| Bookings | Manager, Stylist, Cashier, Front desk, Sales |
| Rooms: cleaning status / bookings / rooms themselves | Front desk + Manager / Front desk + Manager / Manager |
| Trips | Manager, Sales |
| Rent and tenants | Manager, Accounts (new rentals: Manager) |
| Refunds, price changes, discounts | Manager (or a cashier with a manager's PIN for prices and discounts) |

## Running it in production

- **Logs:** set `SPRING_PROFILES_ACTIVE=json` for one JSON object per line (Coolify/Loki friendly). Every
  API request logs method, path, status and time, with a `requestId` (also returned in the `X-Request-Id`
  header) and the `tenantId` on each line.
- **Health:** `GET /actuator/health` is `UP` only when the database answers (components listed, details
  hidden). Point Coolify's health check at it. `/actuator/health/liveness` and `/readiness` also exist.

### Backups (daily `pg_dump` on the Contabo VPS)

`ops/backup.sh` dumps the database from the Coolify Postgres container every night, checks the dump can be
read, keeps 14 days, and can copy each dump off the server with rclone. Install steps are at the top of the
script. Check `/var/log/suite-backup.log` after the first night, and copy a dump off the VPS at least weekly
if rclone isn't set up: a backup on the same disk doesn't survive losing the server.

### Restoring a backup

1. Stop the API in Coolify so nothing writes while you restore.
2. Copy the dump into the Postgres container:
   `docker cp /var/backups/suite/suite_YYYY-MM-DD_HHMM.dump <pg-container>:/tmp/restore.dump`
3. Restore into a fresh database (keeps the broken one until you're sure):
   ```bash
   docker exec -it <pg-container> createdb -U <owner> suite_restore
   docker exec -it <pg-container> pg_restore -U <owner> -d suite_restore --no-owner /tmp/restore.dump
   docker exec -it <pg-container> psql -U <owner> -d suite_restore -c "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO suite_app;"
   ```
4. Point the API at it: set `DATABASE_URL` (and `FLYWAY_URL`) to `.../suite_restore`, start the API, check
   `/actuator/health` and sign in to a workspace. Or swap names:
   `ALTER DATABASE suite RENAME TO suite_old; ALTER DATABASE suite_restore RENAME TO suite;`
5. Practise this once on a spare database before you need it.
