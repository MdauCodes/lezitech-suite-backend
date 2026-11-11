# BizNez

LeziTech's multi-tenant POS + CRM + ERP, sold as **free 7-day trials**. It is the first LeziTech product under
lezitech.site: the app runs at `suite.lezitech.site`, its marketing pages at `lezitech.site/suite`. One product; each business picks a
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

## M-Pesa: each business's own Daraja or PayHero

Money from a business's customers goes straight to that business. LeziTech never holds it. Each business picks one way
for the till to send M-Pesa prompts, under **Settings → Payments** (owner only). The LeziTech team can also set this up
from the control panel (workspace → Payments). That needs the `payments` permission, held by superadmin, admin and
support. The two ways:

- **Daraja (free, no middleman):** the business's own Daraja app from developer.safaricom.co.ke, with:
  - environment: sandbox or production;
  - type: Buy Goods **till** (store number + till number) or **paybill** (paybill number + optional account number);
  - consumer key, consumer secret and Lipa na M-Pesa passkey.
- **PayHero (faster, fewer steps):** the business's own PayHero account with its till or paybill linked as a payment
  channel. It needs the API username and password (PayHero → Account → API Keys) and the channel ID.

Turning one on turns the other off.

"Test connection" does the following:
- **Daraja:** signs in to Daraja. On production it also registers the C2B URLs, so payments made straight to the
  till or paybill show up.
- **PayHero:** reads the PayHero wallet.

How keys are stored and shown:
- Keys are encrypted with AES-256-GCM using `SECRETS_KEY` before they are saved (`integrations` table).
- Screens only ever show the last four characters. Saving with a key left blank keeps the stored one.
- The audit log records which keys changed, never their values.

SMS uses the same pattern. Kind `SMS` holds an Africa's Talking username, sender ID and API key. "Test connection" reads
the balance. Sending comes with the WhatsApp/SMS work.

| Variable | What it is |
|---|---|
| `MPESA_MODE` | `off` (default), `on`, or `simulate` (local practice: no money moves; refused on a real database). `sandbox`/`production` from older setups mean `on`. |
| `MPESA_CALLBACK_BASE` | Public https address of this API, e.g. `https://api.lezitech.site`. Safaricom and PayHero post results there. |
| `MPESA_C2B_SECRET` | Long random string. It is part of the C2B URLs, so nobody else can post fake payments. |
| `SECRETS_KEY` | `openssl rand -base64 32`. Required on a real database. Never change it once businesses have saved keys. |
| `PAYHERO_USERNAME`, `PAYHERO_PASSWORD`, `PAYHERO_CHANNEL_ID` | **LeziTech's own** PayHero account. Businesses pay their BizNez plan into it by prompt. |
| `MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET`, `MPESA_SHORTCODE`, `MPESA_PASSKEY`, `MPESA_BASE_URL` | LeziTech's own Daraja paybill. Used for plan payments only when PayHero isn't set. |

**Till.** Choose M-Pesa, type the customer's number and press "Send prompt". The sale is marked paid only once the
payment is confirmed:
- **Daraja:** the callback arrives on a secret URL and matches the checkout ID and amount.
- **PayHero:** PayHero's callbacks are unsigned, so they only trigger a call to `transaction-status`. The till's
  polling asks PayHero too, in case no callback arrives.

Repeated results are recorded once. If the sale was closed meanwhile, the money is kept as "unapplied" for staff to
refund or use.

**Direct till payments (C2B).** These reach BizNez at `MPESA_CALLBACK_BASE/api/public/mpesa/c2b/<MPESA_C2B_SECRET>/confirmation`.
A payment is matched to a business by its Daraja till, paybill or store number. If the customer typed the sale number
as the account, the sale is paid automatically. Otherwise it waits in `GET /api/mpesa/till-payments` for staff.

**Plans.** Billing → "Pay now with M-Pesa" sends a prompt from LeziTech's own PayHero account. The plan switches on
once the payment is confirmed. The manual request ("Pay another way") and the control panel's billing queue still
work as the fallback.

**Local runs.** `.claude/launch.json` runs the API with `MPESA_MODE=simulate`. Every business can send prompts even
with nothing set up, and a phone number ending in 9999 acts as a customer who cancels.

**PayHero API.** The calls follow PayHero's v2 API as used by its official PHP package:
- `POST /payments`
- `GET /transaction-status?reference=`
- `GET /wallets`

The docs site needs a browser to read, so confirm the field names with one real sandbox payment before go-live.

## KRA eTIMS (switched off for now)

eTIMS is not offered at launch. The code is kept so it can be switched on later, but `ETIMS_MODE` stays `off`
(the default), nothing is sent to KRA and no screen mentions it. Receipts still print the business's KRA PIN.
Instead, businesses get a tax summary that estimates what they owe per day, month and year.

The pieces that stay: the `EtimsClient` interface and a stub client in `src/main/java/.../etims`, the
`etims_queue` table, and receipt fields that only show when an invoice is signed. To bring it back: get LeziTech
approved as a KRA third-party integrator, write the OSCU client against KRA's sandbox, pass certification, then
switch `ETIMS_MODE` on and restore the Settings hint.

## Not built yet (next steps, roughly in order)

1. Per-location prices; shop-requests-stock-from-store flow.
2. Accounting ledger (journal postings, P&L, balance sheet).
3. Mall / landlord product (rent, service charge, occupancy) as a separate module, per the 29 Oct notes.
4. `.xlsx` import; a screen for unmatched direct M-Pesa till payments (the API exists).

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

## LeziTech control panel (`/admin`)

For the LeziTech team only; separate login and token (a workspace token is refused by `/api/admin/**` and an
admin token is refused by workspace endpoints).

- **Overview:** MRR, trials, trial→paid conversion, signups per day, by sector, trials ending (who to call), latest team actions.
- **Workspaces:** search and filter every business; detail page with usage (orders, sales, own items added,
  activity chart), people and last sign-ins, team notes, links to their staff login/menu/order board.
  Actions: activate a plan, extend a trial, suspend/reactivate, switch screens and features for that workspace,
  and **open their portal as any of their roles** to see exactly what they see.
- **Billing queue:** owners who chose a plan; confirm the M-Pesa code and activate.
- **Sectors & packs:** pause or reopen signups per sector.
- **Demo businesses:** reset any demo to fresh data.
- **LeziTech team:** add teammates as superadmin, admin, support, sales or developer (permissions per role).
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

## Switching cashier at the till

On the till, "Switch cashier" lists staff who have a PIN; the next person taps their name and types their PIN
(`POST /api/auth/pin`, rate-limited like the password login). The cart stays, and sales from then on are recorded under
that person and their own cash drawer. PINs are set under Staff.

## Selling without internet

Counter sales keep working when the internet drops. The sale is saved in the browser (IndexedDB), the receipt says
"offline, number given when sent", and a strip at the top shows how many are waiting. When the connection is back
(and every 20 seconds) they are sent oldest first with their checkout key, so a sale that did reach the server before
the line dropped is never doubled. The server gives the real number and keeps the time the sale was made
(`offlineAt`, at most 7 days back); such sales are tagged "offline" in Sales.

- The products list and the signed-in person are kept on the device, and in the production build a service worker
  (`public/sw.js`) keeps the app itself, so the till also opens after a reload without internet.
- Not offline: kitchen bills, M-Pesa prompts and "pay later" (they need the server). Typing an M-Pesa code works.
- A sale the server refuses when it arrives (say, out of stock where selling below zero is blocked) waits under
  "See them" with the reason; fix it and press Try again. Only an owner or manager can remove one.

## Trial reminders

Every hour the API looks for trials ending soon and reminds the owner once on day 5 ("2 days left") and once on
day 7 ("ends today"), with a link to Billing where they can pay by M-Pesa. Paying or demo workspaces are skipped.

- Email: `EMAIL_PROVIDER` = `brevo` or `resend`, with `EMAIL_API_KEY`, `EMAIL_FROM` (a verified sender) and optionally
  `EMAIL_FROM_NAME`; `APP_URL` is the web address used in the link; `SUPPORT_WHATSAPP` adds "WhatsApp us on …".
  `log` writes emails to the log instead (local runs); `off` (default) sends none.
- WhatsApp: there is no automatic WhatsApp sending. Each reminder also gets a ready message, and the control panel's
  **Trial reminders** page lists them with an "Open WhatsApp" button (wa.me link to the workspace phone) and
  "I sent it", which is written to the audit log. "Check now" runs the hourly check straight away.

## Plan limits

| Plan | People who can sign in | Branches | Products and services |
|---|---|---|---|
| Lite | 2 | 1 | 500 |
| Growth | 8 | 3 | 5,000 |
| Pro | 30 | 10 | unlimited |

Checked in the API (`PlanLimits`) when adding or switching back on a staff member, adding a branch (a store inside
a branch doesn't count) and adding or importing products. Going over is refused with `PLAN_LIMIT` and a message
naming the plan that allows more; the web app shows it in a window with "See plans" for the owner. During the free
trial Pro's limits apply. Nothing already there is removed when a plan is smaller; only new additions are stopped.
Your plan (Billing) shows "used of limit" for each.

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
