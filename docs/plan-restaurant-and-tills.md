# Build plan: restaurant QR ordering and the till changes

Written 2026-11-11. Status: **plan, waiting for the owner's go-ahead**. Go-live: 22 November 2026.

- The design comes from `restaurant-table-ordering.md` (the QR plan) and `platform-enhancements.md`.
- **`decisions.md` wins wherever they disagree.**
- This file says how it gets built, in what order, and what is checked.
- **Keep it current while building.** Each package's commit updates its "Status" line here and the "built so far" notes in `restaurant-table-ordering.md`.

---

## 1. Scope

**Before go-live, all of it.** The waiting client wants every feature in the QR plan (`decisions.md`, 2026-11-11):

- **Ordering:**
  - a QR code per table, with printable cards;
  - order from the phone, with a review step before sending;
  - group orders across tables.
- **Kitchen:**
  - sees the order at once;
  - estimated time;
  - mark items unavailable;
  - reject an item and offer a substitute.
- **Diner's phone:**
  - live tracking;
  - pay by M-Pesa, by one person or split across phones;
  - tips, rating, and a pickup code for takeaway.
- **Restaurant:**
  - waiter auto-assignment;
  - photos and allergens on the menu;
  - loyalty points;
  - order board screens per area.

Plus the till and platform changes the owner decided:

- **Drawers:**
  - blind count at close;
  - float required before the first sale;
  - owner reconciliation that locks the cashier's sales, with unlock;
  - cashiers see their own shifts.
- **Plans:** Lite at KSh 2,650 with 3 staff.
- **Tax receipts and tax due:**
  - the business uploads its ETR (photo or PDF) and it is emailed to the customer;
  - the Tax page shows tax due per week, per month or for any dates.
- **Support access:** only when the owner grants it; 5 hours; the owner can stop it; everything is logged.
- **Files:** Cloudflare R2 from day one.

**Honest note on time:** the QR plan estimated 8 weeks. Fitting it into about 9 working days means:

- every package is cut to what a restaurant needs on day one;
- polish comes after launch, but correctness, security and tests do not get cut;
- packages land in order, each tested and usable on its own, so whatever is done on 21 Nov works;
- if something must slip, the owner chooses what (section 8).

---

## 2. What already exists (checked in the code, 2026-11-11)

| Piece | Where |
|---|---|
| Floors and tables (label, seats, position) | `restaurant/RestaurantController` (`/api/floors`, `/api/tables`), `pages/Floor.tsx` |
| Kitchen screen, per line and whole-ticket bump, stations (kitchen/bar) | `/api/kitchen`, `pages/Kitchen.tsx` |
| Public menu (categories, items, add-ons, sold out), read only | `GET /api/public/menu/{slug}`, `pages/public/Menu.tsx` |
| Public tracking by order token, with stage | `GET /api/public/track/{slug}/{token}`, `PublicService.track`, `pages/public/Track.tsx` |
| Order board TV screen, protected by a display key | `GET /api/public/board/{slug}?k=`, `pages/public/Board.tsx` |
| Split bill, send to kitchen, courses, kitchen tickets printing | `OrderService`, `Pos.tsx` |
| M-Pesa prompts to the business's own Daraja or PayHero account, with signed/secret callbacks | `mpesa/MpesaService` |
| Shifts per person: float, count per method, expected cash, short/over logged | `sales/ShiftController`, `components/pos/ShiftBar.tsx` |
| Activity log, problems, background email jobs | `activity/`, `notify/` (V17) |
| Tax estimate per day/month/year; Turnover Tax 1.5% default | `reports/TaxController`, `pages/Tax.tsx` |
| LeziTech staff can sign in as a business owner (audited, **no owner consent today**) | `AdminController.impersonate` |

There is no file storage yet (logos are links), and the menu cannot take orders today.

---

## 3. Corrections to the QR plan's technical sections

The QR plan's SQL and endpoints are a sketch. These change when building:

- **People are `users`, not `staff`.** Waiters, approvers and support grants reference `users(id)`.
- **Money is `numeric(14,2)`,** like the rest of the schema, never `integer` (tips, loyalty value).
- **Every new table carries `tenant_id` and the row-level-security fence.** `RlsCoverageTest` fails otherwise. Tables read without a sign-in (public ordering) are reached through `TenantContext.runAs` after the restaurant is found by slug, as the public menu does today.
- **QR links use the app's address** (`/menu/{slug}?table={tableId}`), not `lezitech.site/s/...`. Tracking stays `/track/{slug}/{token}`.
- **The pickup code is random,** 4 digits, stored on the order. It is not derived from `hashCode()`, which is guessable and collides.
- **ETR is free** and is the business's own KRA receipt, uploaded and emailed. There is no "ETR checkbox at KSh 4", and there is no generated PDF pretending to be an ETR.
- **Cards print from the browser** (a print page sized for folded table cards, "Save as PDF" works). This avoids a server PDF library; it can be added later if needed.
- **Public endpoints are rate limited:**
  - orders per table and per phone;
  - payment prompts per order.
  - Order tokens stay unguessable UUIDs.
- **A self-order uses the restaurant's own rules:** prices from the server, sold-out checked, add-ons checked. That is the same `OrderService.addLines` path as the till, never prices sent by the phone.

---

## 4. Work packages, in order

Each package has the same five parts, and none is done without them:

1. migration;
2. API;
3. screens;
4. tests;
5. documentation.

Targets are calendar days.

### A. Foundations (12 Nov)
- **V18 migration**, holding everything below so later packages don't fight over numbers:
  - orders: `assigned_to`, `pickup_code`, `estimated_ready_at`, `self_order`, `batch_id`, `tip`, `rating`, `rating_comment`;
  - order lines: `rejected`, `substitute_item_id`, `substitute_status`;
  - items: `photo_key`, `allergens`, `unavailable_until`, `prep_minutes`;
  - shifts: `reconciled_at`, `reconciled_by`, `locked`;
  - new tables: `files`, `loyalty_accounts`, `loyalty_moves`, `board_screens`, `board_screen_tables`, `support_grants`, `order_etrs`;
  - all fenced; the RLS allowlist test updated.
- **File storage:**
  - `FileStore` with an R2 implementation (S3 API, keys from env vars);
  - a disk implementation for local runs and tests;
  - uploads checked for type and size, images shrunk;
  - files served through short-lived signed links.
- **Restaurant settings:**
  - pay when ready or pay first (default: when ready);
  - tip presets (300/500/1,000/3,000) and tip shares (kitchen 45%, business 10%, server 45%);
  - self-ordering on or off.

### B. Order from the table (13–14 Nov)
- **Floor screen:**
  - "QR codes" shows each table's code;
  - "Print table cards" opens a print page in the restaurant's logo, colours and tagline, with a small "BizNez by LeziTech" line at the foot.
- **Menu page:**
  - `/menu/{slug}?table=…` shows "Table 5", a cart, add-ons, a note per item, and name and phone (optional);
  - a **review step**, then "Send to kitchen";
  - nothing can be changed after sending.
- **`POST /api/public/orders`:**
  - creates a dine-in order for that table through `OrderService`;
  - sent straight to the kitchen when the restaurant pays-when-ready, held as "waiting for payment" when it pays-first;
  - returns the tracking link.
- **Waiter auto-assignment:**
  - the order goes to the active waiter with the lowest load (open tables + plates), among those signed in today;
  - an order a waiter enters stays theirs.
- **Takeaway self-orders** get the random pickup code.
- **Tests:**
  - prices come from the server;
  - sold-out items are refused;
  - the rate limit holds;
  - the order lands for the right business only;
  - the waiter with the lowest load is picked.

### C. Kitchen and tracking (15 Nov)
- **Estimated time:**
  - the kitchen sets or changes minutes on the ticket;
  - the system suggests one from each item's average past cooking time (the last 30 days), else from `prep_minutes`.
- **Mark unavailable:**
  - "out until restocked" or "until a time";
  - the menu shows it as sold out.
- **Reject with substitute:**
  - the kitchen rejects a line and offers an item;
  - the diner accepts or declines on the tracking page;
  - if declined, the line is removed, the bill drops, and the waiter is alerted;
  - logged in the activity log.
- **Tracking page:**
  - live stages (checked every 10 seconds), the estimate with a countdown, "running a bit late";
  - the substitute question, the pickup code, and the table.
- **Floor and "My tables" for waiters:**
  - colours: not acknowledged, in kitchen, ready, paid;
  - assigned orders and their own load.

### D. Pay from the phone (16 Nov)
- "Pay with M-Pesa" on the tracking page appears when the food is ready, or straight away if the restaurant pays first.
- The prompt goes to the **restaurant's own** till or account, as today.
- **Tip step:**
  - a custom amount first, then the presets;
  - tips are recorded separately from sales (not VAT'd as food), with shares per the restaurant's split.
- **Split payment:**
  - equal, custom amounts or percentages across phones;
  - prompts sent one at a time;
  - a failed phone is retried after the others;
  - "2 of 3 paid".
- **Paying first:** the order reaches the kitchen only when fully paid.
- **After payment:**
  - a rating (5 stars and a comment);
  - waiters see "Paid, ready to serve".
- **Self-service payments in reconciliation:** they have no cashier, so they appear under "Paid by phone (QR)", M-Pesa only, with nothing to count.
- **Tests:**
  - only the token holder can pay;
  - split totals add up exactly;
  - double callbacks are recorded once;
  - pay-first holds the kitchen.

### E. Group orders (17 Nov)
- "Group order" on the menu:
  - pick the tables, build each table's order, one combined bill;
  - pay by one person or split as in D.
- Orders share a `batch_id`. Each table is tracked separately, and the board shows each table.

### F. Photos, allergens, board screens (18 Nov)
- **Items screen:**
  - photo upload (R2), shown on the menu;
  - allergens chosen from a list (nuts, gluten, dairy, eggs, fish, shellfish, soy, sesame) plus free text;
  - "Allergens" opens under each item on the menu.
- **Board screens:**
  - the owner names screens and assigns tables;
  - "suggest" splits tables evenly;
  - each screen has its own link, with the display key kept, since boards show customer names;
  - the screen auto-scrolls and sizes text for the number of tables.

### G. Loyalty (19 Nov)
- Off by default.
- Set-up wizard:
  - points per KSh spent;
  - value of a point when redeemed;
  - minimum points to redeem;
  - expiry (none, or months).
- Points are earned when a paid order has a phone number. They are redeemed as a discount at the till or on the phone (needs a code sent to that phone).
- The customer's points show in Customers.
- Every move is recorded.

### H. Till changes (20 Nov)
- **Blind count:** the close-shift API stops returning expected amounts to cashiers; only owner and manager get them. The screen asks for counts without hints.
- **Float required:** anyone taking payment must open their till with a float before the first sale; the till blocks until then. Self-orders are exempt (no cashier).
- **Reconciliation screen** (owner, manager, accountant):
  - per cashier per day: float, sales by method, expected, counted, difference;
  - status matched / difference noted / investigated, with a note;
  - "Reconcile" locks those shifts: voids, refunds and edits on their sales are refused;
  - "Unlock" is owner only and is logged in the activity log.
- **Cashiers see their own shift history**, without expected amounts or differences.
- **Lite plan:** KSh 2,650, 3 staff. Change `PlanLimits` and the plan list, and check the billing amounts and the control panel's revenue figures.

### I. Tax receipts, tax due, support access (21 Nov)
- **ETR upload:**
  - on any paid sale, "Attach ETR" (photo or PDF, to R2) and "Email ETR" to the customer through the background email jobs;
  - failures go to Needs attention, as receipts do;
  - free.
- **Tax page:** tax due for this week, this month, or any dates, with sales that have an ETR attached vs not, so the owner sees what still needs a KRA receipt. It works without eTIMS.
- **Support access:**
  - Settings → "Let LeziTech help": the owner grants 5 hours and can stop it any time, with a countdown;
  - control-panel sign-in-as-the-business works **only while a grant is active**;
  - every action during it is written to the business's activity log, marked "LeziTech support";
  - this changes the existing `impersonate` endpoint.

### 22 Nov: go-live
Deploy (test server first, then live), using the owner's Contabo/Coolify access, Brevo key and DNS, the PayHero sandbox payment and the R2 keys.

---

## 5. Checks for every package
- API tests for the new rules (as in sections B–I), and the full API suite passes.
- Web typecheck is clean.
- Tried in the browser on desktop and on a 375px phone. The diner's pages are phone-first.
- A diner, a waiter, the kitchen and the owner are each walked through in the demo restaurant (Mama Oliech Kitchen).
- Docs updated in the same commit, as described at the top of this file.

## 6. What the owner provides, and by when
| What | Needed by | Why |
|---|---|---|
| Cloudflare R2 bucket, access key, secret, account id | **14 Nov** | Photos (F) and ETR uploads (I); local work uses disk until then |
| The waiting client's menu, tables and staff list (or access to set them up) | 15 Nov | Test with real data before launch |
| Contabo/Coolify access, Brevo key and DNS, PayHero sandbox payment | 20 Nov | Deploy on 22 Nov |

## 7. Defaults chosen here (say if any should change)
- **Self-ordering** is on for restaurants and hotel restaurants, off for other sectors.
- **Waiters eligible for auto-assignment:** role WAITER, active, and signed in today. Managers can reassign.
- **Rate limits:**
  - 5 orders per table per hour;
  - 3 payment prompts per order per 10 minutes.
- **Tips:** not counted as sales for VAT; shown in a tips report per server.
- **Float required:** for everyone who takes payment, owners and managers included, so every shilling is counted.
- **Reconcile by day:** all of a cashier's shifts that day reconcile and lock together.

## 8. If time runs short
Packages land in order, so the latest ones are at risk first: G (loyalty) and I (ETR, support access).

- The owner decides on 18 Nov, looking at what's done, whether to launch with everything or move the last package a few days after go-live.
- Nothing half-built is switched on: an unfinished package stays hidden.
