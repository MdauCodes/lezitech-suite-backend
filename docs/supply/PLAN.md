# Busynez Supply: plan

A system for suppliers who buy goods at the market and deliver them to hotels, hospitals and restaurants on standing orders. First prospects: Voi (3 hotels, a small restaurant, a hospital) via Dreddah. Built so similar suppliers anywhere in Kenya can use it.

Mockups: https://claude.ai/artifact/DHuTzpMn5mEHrKYV8J3UpB (source `mockups/index.html`).

## The selling point

"You will see who took what." Staff theft is why these owners will pay monthly. Every screen is judged by one question: does it make theft visible and hard at the stall, in the vehicle and at the client's door?

Design rule that delivers it: **every handover has two names.** Buyer to store (store weighs and compares), store to vehicle (loader loads, driver accepts the load on his own phone), vehicle to client (the client's clerk confirms, the driver cannot edit it), cash to owner (evening cash-up). When an amount drops between two names, the "What doesn't add up" view shows it in red with who held the goods at that step.

## Who uses it

| Person | Device | What they do |
|---|---|---|
| Owner | Phone or laptop | Dashboard, gaps view, money and profit, cash-up, prices, staff |
| Market buyer | Cheap Android phone, often no network | Takes the day's list and a cash float, saves each purchase with seller, weight, price, paid by, receipt photo |
| Storekeeper / packer | Android phone at the store | Weighs goods in, sorts and grades, records waste (reason + photo required), loads vehicles |
| Driver | Android phone | Accepts the load, follows stops, gets each delivery confirmed, records sent-back goods, breakages (photo) and cash collected |
| Accountant | Laptop | Invoices, statements, reminders, expenses, M-Pesa matching, wages |
| Client's receiving clerk | Their own phone, no account | Opens an email link, fills in what was received and sent back, signs, confirms. No phone at the door: reads a 4 digit code from the email to the driver |

Roles map to existing Busynez roles: OWNER, MANAGER, ACCOUNTANT, STOREKEEPER, RIDER (driver), plus a new BUYER role. The client clerk is not a user: a signed one-time link per delivery note.

## Screens

Mocked up (in the artifact):
1. Owner dashboard: today's gaps band, sold / spent / profit / cash still with staff, one item's chain, deliveries with the clerk's answer, stock in store, clients not yet paid.
2. What doesn't add up: per item Bought, Sorted, Loaded, Delivered, Paid for, with names; red gap line with "Ask X to explain" and "Settle this gap"; cash gaps; month table of gaps per person (shown as a question to ask, not proof).
3. Market buyer phone: money in hand, today's list progress, add purchase with price alert against other buyers' prices that day, receipt photo, offline banner.
4. Store phone: weigh goods in against the buyer's figure, sort into grades, waste with reason and photo (save blocked until weights add up), load vehicle with driver acceptance.
5. Driver phone: accepted load, stops in order, clerk-confirmed amounts (locked), sent-back goods, emailed code for clients with no phone at the door, cash collected.
6. Client confirm delivery: delivery note, received and sent back per line, reason for sending back, signature, tick box that says what happens, invoice follows what was confirmed.
7. Money and profit: month totals including money lost to gaps, 7 day chart, profit per client and per item, clients on terms with statement and reminder buttons, today's expenses, invoice with optional KRA PIN and optional eTIMS.
8. Evening cash-up: each person who carried money, should hand in vs counted, release button; a gap must be settled first (from next wage / pays now by M-Pesa / let go with a reason). Today's team with roles, time in, wage, advances.
9. Orders and prices: standing orders and LPO orders, price list per client, tomorrow's buying list (orders less stock, optional 5% extra for waste), send list and float to the buyer.

Still to design (not in the mockups): staff setup and PINs, attendance clock-in, wages run, client setup (terms, contacts, receiving phone numbers), item setup (units, grades), vehicles, branches/markets switcher, supplier (seller) list, settings, sign-up with sample data off by default.

## Features

- **Orders**: standing orders (days, items, amounts, until stopped), one-off orders with LPO number and photo, prices per client, price alert when buying price passes a client's price.
- **Market buying**: daily buying prices, who bought what from whom, how much paid, cash or M-Pesa, receipt photo, float tracking, comparison with other buyers and yesterday.
- **Stock**: goods in weighed against purchase, sorting and grading, sold by weight or count (kg, trays, litres, bundles, crates, bags), waste with reason, photo and who recorded it, stock count.
- **Dispatch**: what was loaded on which vehicle, by whom, accepted by which driver, delivery notes.
- **Delivery proof**: clerk confirms by email link (signature) or a 4 digit code from the email; short, sent back and returns recorded; returns must be weighed back into the store.
- **Money**: M-Pesa (STK, till C2B, matching), cash, bank; client accounts on terms with statements and reminders; expenses (transport, fuel, market fees, porters, wages); profit per client, per item, per day, per month; money lost to gaps.
- **Staff**: roles, attendance, wages, advances, deductions from settled gaps, evening cash-up with release.
- **Gaps view**: bought vs sorted vs loaded vs delivered vs paid, plus cash, every step named.
- **Kenya-wide**: several markets and branches, English with Swahili next to main phone buttons, cheap Android phones, works offline and syncs later, eTIMS optional, KRA PIN optional everywhere.

## What Busynez already has (scout of `launch`, 25 Nov 2026)

API `J:\backend\lezitech-suite-api` (Spring Boot, JDBC, Postgres with RLS, migrations to V76). Web `J:\frontend\lezitech-suite-web` (React 18, Vite, TS, Tailwind, TanStack Query).

| Need | Already in Busynez | Gap |
|---|---|---|
| Stock, goods in | `stock/StockController`, `StockTakeController`, `purchasing/PurchaseController` | No buyer float, no weigh-in vs purchase check |
| Sell by weight, units | `items.sold_by_weight` (V44), buy/sell units and packs (V37, V54) | Grades (A/B) per item |
| Waste | `restaurant/WasteAndBar`, `waste_entries` with reason and user | Photo required; open to storekeeper, not manager only |
| Roles | OWNER, MANAGER, ACCOUNTANT, STOREKEEPER, RIDER, COLLECTOR and more (`team/TeamController`, `config/RoleGuard`) | BUYER role |
| Cash-up | `sales/ShiftController` (float, blind count, reconcile), staff release in `restaurant/WaiterClearing*` | Generalise release to buyer and drivers; settle options |
| M-Pesa | `mpesa/` STK, PayHero and Daraja, C2B with apply/ignore | Statement-level matching |
| Branded email | `notify/BrandedEmail`, `EmailLayout`, `EmailSender` | Delivery confirm email with link and code |
| Activity log | `activity/ActivityLog` (money and stock events) | No before/after record of edits, though `docs/plan-strategy.md:142` claims one. The gaps view needs every handover logged and confirmed amounts locked |
| Clients on terms | `crm/CustomerLedger`, `crm/StatementController`, credit limit | KRA PIN on customer, reminders, per-client price list |
| eTIMS | `etims/` (`ETIMS_MODE=off`) | Turn on per business, optional |
| Branches | `catalog/BranchesController`, plan limits | Markets as places to buy |
| Offline | `src/lib/offline.ts` IndexedDB queue with idempotency keys | No service worker yet, so the app does not open with no network |
| Sectors | `sectors.json` (11 keys incl. wholesale) and `subsectors.json` | `docs/sector-study.md` already lists wholesale gaps: delivery notes, ageing, pack units |

New to build: vehicles and loads with driver acceptance, delivery notes with public confirm link and code sent by email, standing orders, buyer float and market purchase screen, the gaps view, cash-up settle options.

## Busynez sector or separate product?

**Recommendation: build it as a Busynez sector (wholesale sub-sector "Supplier to hotels and hospitals"), sold under its own name "Busynez Supply" with its own landing page.**

Why:
- About two thirds of what it needs is already live and tested on `launch`: stock, weight, waste, roles, cash-up, M-Pesa, statements, eTIMS, branches, branded email, offline queue. A separate product would rebuild all of that.
- The new parts (deliveries, confirm link, gaps view, standing orders) also help existing wholesale and distributor sectors, which `docs/sector-study.md` already says lack delivery notes.
- One codebase, one sign-in, one billing system. The hospital and school systems that come next can reuse the same delivery and gaps parts.
- The phone screens for buyer, store and driver should be a simple separate layout inside the same web app, not the full Busynez menu.

Against it: Busynez menus and wording are shaped around shops and restaurants, so the sector needs its own home screen and its own words. Separate product only makes sense if a client needs to host it themselves.

## Owner's decisions (25 Nov 2026)

1. **Built as a Busynez sector**, sold as "Busynez Supply".
2. **Clerk confirmation by email link by default** (email is the most available). Clerks with no phone at the door read a 4 digit code from the delivery email to the driver.
3. **No SMS for now.** Links, codes, statements and reminders go by email (Busynez branded email already exists).
4. **Gaps come off wages only when the owner taps** at cash-up (from wage, pays now, or let go with a reason).

Still open: the monthly fee and plan for this sector. Also a photo of a signed paper note as a fallback was not chosen; leave it out unless a client asks.

## House rules followed

Plain words; tick boxes say what happens; no "owe", "owed", "due"; every action is a labelled button; no em or en dashes; no small uppercase labels above headings; sample data off at sign-up (stated on the mockup).
