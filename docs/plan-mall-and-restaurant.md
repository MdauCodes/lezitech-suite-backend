# Study and plan: Malls & property, and Restaurants

Written 2026-11-13 at the owner's request: "work on the Mall and the Restaurant systems so they are complete and very
personalised for the use cases of the businesses that use them. Study what we have, what is in the market (we must surpass
it by far), and what we aim for."

**How to read this:** section 1 is what we have today (checked in the code). Section 2 is the market. Section 3 is each kind
of business and what it needs. Section 4 is what we aim for, including the things nobody else does. Section 5 is the build
order with time estimates. Section 6 lists the decisions the owner needs to make.

A note on the market sources: almost every Kenyan "best POS" or "best rent app" article is written by one of the vendors it
ranks. The features listed below are what each vendor *says* it does. Nobody has tested them side by side.

---

## Progress (newest first)

**2026-11-14: M5, the tenant portal, is built, tested and checked in the browser.** Its answers are below, under M2.

What M5 built:
- **Tenant sign-in** (`property/portal/RenterAuth`):
  - Accounts are per business (`renter_accounts`), and codes are emailed (`renter_codes`).
  - The landlord invites the tenant (code valid 3 days), or a tenant on an active lease asks for a code (valid 15 minutes, 4 an hour). Then they set a password.
  - Tenants get their own token (scope `renter`). It reaches only `/api/portal/*` (JwtFilter), and staff tokens are refused there.
  - A password change ends older sign-ins.
- **Tenant's portal** (`PortalController`, web `/t/{slug}`, phone first):
  - Home shows owed, overdue and due date, the M-Pesa account number, pay by prompt, and the statement.
  - Repairs and complaints with photos, messages and a rating.
  - Notices.
  - Documents.
  - Notice to vacate.
- **Landlord** (`PortalAdminController`, web Rent & tenants):
  - A Repairs tab: give the job to someone, set status and cost, message the tenant or leave a staff-only note, add photos, charge the tenant (a `REPAIR` bill).
  - A Notices button: to all, a floor, or chosen tenants; emailed too.
  - In the tenant panel: tenant-page invite and status, and documents.
  - "Needs you" also shows notices to move out and new problems no one has been given yet.
- **Caretaker / technician** (new staff job `TECHNICIAN`): a Repair jobs page showing only the jobs given to them (`/api/jobs`). They can mark a job being fixed or done, add a photo and write to the tenant.
- Migration `V29__tenant_portal.sql`.
- **Tests:** `TenantPortalTest` (4). Full API suite **145 passing**.

**Owner's request, 2026-11-14, for after all this is done:** a happy client adding more businesses, branches, or businesses in other sectors under one account. Brainstorm it with the owner then; don't build it now.

**Remaining mall packages:** M3 (meters and service charge), M4 (markets and daily collections), M6 (agents and rental tax), M7 (floor map and units to let), M8 (turnover rent and mall directory), M9 (food court in one QR).

---

**2026-11-13: M2 "The lease's life" is built and tested. Next: M5, the tenant portal (moved up at the owner's request).**

"Lease" always means the agreement between a landlord (our client) and their tenant. It never means BizNez's own plans or billing.

The owner's answers for M2:
- **Rent increases:**
  - Written in the lease as a % or a fixed amount every N months.
  - The owner must approve each one and can change the amount or skip it.
  - Once approved, the tenant is emailed a notice, and bills use the new rent from that month.
- **Deposit:**
  - Billed like rent when the lease starts, or ticked "already received".
  - It's money held for the tenant, not income: deposit bills (channel `DEPOSIT`) are left out of sales, the dashboard and the tax figures.
- **Move-out:**
  - The deposit held, minus unpaid rent and bills, minus the deductions the owner lists.
  - If money is due to the tenant, the system shows it, and the owner records the refund after paying it themselves.
  - If the tenant still owes, that shows instead.
- **Lease end:**
  - Alerts at 90, 60 and 30 days, by email to the owner and under "Needs you".
  - If nothing is done, billing carries on monthly.
  - Renewal is one step.

What M2 built:
- **API:**
  - `LeaseLife`: increases (terms, proposals 30 days ahead, approve, skip, applied when billing), deposit bill and held amount, renew, move-out preview and move-out, refunds, the lease's history (`lease_events`), what needs the owner (`/api/property/attention`) and lease-end alerts.
  - `RentJobs` emails the owner about increases to approve and leases ending.
  - Migration `V28__lease_life.sql`.
- **Web:**
  - A "Needs you" card: approve or skip increases, renew, moving out, and "I've paid it back".
  - The lease form gains the deposit ("I already received it"), an end date and rent increases.
  - The tenant panel shows deposit held, the next increase, Renew, Moving out and the history.
  - The move-out window shows the settlement live as deductions are typed.
- **Tests:** `LeaseLifeTest` (4). Full API suite **141 passing**.

**M5, the tenant portal (owner's answers, 2026-11-13). To build next:**
- **Getting in:**
  - The landlord invites the tenant (an email with a code), or the tenant enters their email.
  - The first sign-in is a 6-digit code emailed to them, then they set a password.
  - After that they sign in with email and password, and can reset it by code.
  - The landlord can send a new code at any time.
- **What a tenant can do:**
  - Pay rent: an M-Pesa prompt to their phone, which goes on their bill by itself; receipts and statement are there.
  - Complaints and repairs: with photos and progress (received, being fixed, done). The landlord assigns the job, records the cost, can charge it to the tenant, and the tenant can rate the fix.
  - Read notices from the landlord, to everyone or to one floor; they also arrive by email.
  - See their lease details and documents, and give notice to vacate, which alerts the landlord and starts the move-out.
- **Who handles complaints:** the owner and managers see all of them. A caretaker or technician (a staff login with PIN) sees only the jobs assigned to them, and marks them done with a photo.

---

**2026-11-13: owner chose "Mall first". M1 "Get paid" is built and tested.**

The owner's answers for M1 (2026-11-13):
- **Payments:**
  - The main way is an M-Pesa prompt through the landlord's own PayHero or Daraja.
  - Otherwise the landlord checks and records payments by hand.
  - Paybill payments that arrive with a unit's account number go on that tenant's bill by themselves. Any others wait in a list for the landlord.
- **Overpayment** is kept as credit and used by the next bills.
- **Penalties:**
  - One per late bill, after grace days, only where the lease has one.
  - The owner can lift a penalty with a reason (logged).
  - Each month stands on its own.
- **Reminders:**
  - Email is built in.
  - SMS (Africa's Talking) and WhatsApp are set up by LeziTech per client on request, quoted separately at a fair price.
- **Working rule from the owner:** always ask when there's a question; don't guess.

What M1 built:
- **API** (`property/`):
  - `LeaseLedger`: receipts spread oldest bill first, extra as `leases.credit`, credit used by new bills, penalties, lifting, statements with ageing.
  - `RentJobs`: hourly from 08:00 Nairobi. It makes the month's bills (setting `autoInvoice`), adds penalties and sends email reminders before, on and after the due day, each once.
  - `RentSettings` (`settings.rent`).
  - `RentPaymentsController`: prompt, paybill list, assign, ignore.
  - `PublicStatementController`: `/api/public/statement/{slug}/{token}`.
  - The unit's M-Pesa account number: `spaces.account_code`, or the unit name tidied (G-01 → G01).
  - A typo'd unit code is never matched to an invoice number for a landlord.
  - Migration `V27__rent_collection.sql`.
- **Web:**
  - Rent & tenants has tabs: Units, This month, Who owes, To match.
  - The tenant panel shows what's owed, credit, account number and penalty, with these actions: M-Pesa prompt, record payment, statement.
  - The statement can be printed or saved as PDF, the link copied or sent on WhatsApp, a new link made, and a penalty lifted.
  - Rent settings.
  - The tenant's own statement page is at `/statement/{slug}/{token}`; on a phone it shows as a list.
- **Tests:** `RentCollectionTest` (5). Full API suite **137 passing**.

(M2 followed: see above.)

---

## 1. What we have today

### Restaurants (strong)

| Area | Built |
|---|---|
| Selling | Tables and floors, courses, add-ons, split bills, tips, takeaway and delivery channels, offline till, PIN-only staff |
| Kitchen | Kitchen screen per station (kitchen, bar, barista), bump per line or ticket, estimated time, reject a dish with a substitute, mark sold out |
| Diner's phone | QR per table, order from the phone with a review step, live tracking, pay by M-Pesa (one phone or split), group orders across tables, rating, pickup code |
| Screens | Order-ready TV board per area, table QR screen, three-sided table cards |
| Guests | Loyalty points on the owner's rules (earned, spent at the till, given back on refunds) |
| Money | Owner's own M-Pesa (Daraja or PayHero), float, blind count, till days the owner locks, ETR receipts attached and emailed, VAT shown only when VAT-registered |
| Menu | Photos, allergens, per-area screens |
| Sub-types | Restaurant, café, bar & lounge, fast food, bakery, restaurant with rooms, ice-cream & desserts, each with its own words and sample menu |

**Missing (from `sector-study.md` and the code):**
- recipes, so selling a dish uses up its ingredients
- a waste log
- food cost % per dish
- bar tots (a bottle sold by the glass)
- table reservations and a waitlist
- delivery addresses and riders
- delivery-app orders (Glovo, Uber Eats, Bolt Food)
- happy hour and combos
- bakery production and cake orders
- stopping a diner paying a bill that isn't theirs (still open from 12 Nov)

### Malls & property (thin)

Only `PropertyController.java` (142 lines), the `spaces` and `leases` tables, and one screen ("Rent & tenants").

| Area | Built |
|---|---|
| Units | Shops, stalls, offices, apartments, with floor, size, rent and service charge |
| Leases | Start, end, rent, service charge, deposit, due day; end a lease |
| Billing | "Raise this month's invoices" (rent plus service charge at 16% VAT); safe to run twice |
| Reports | Rent roll for a month (billed, paid, overdue); arrears per unit; occupancy count |
| Sub-types | Mall, market stalls, commercial building, apartments, property agent. **All five behave the same.** |

**Missing:**
- tenant statements
- M-Pesa paybill payments matched to the unit by account number (the payment arrives, but nothing ties it to a tenant)
- reminders
- penalties for late payment
- rent increases each year
- water and electricity meter readings
- service-charge budget and year-end reconciliation
- deposits held and refunded
- lease expiry alerts and renewals
- daily stall collections
- maintenance requests
- a page for tenants
- landlord statements and payouts for agents
- rental income tax
- a picture of the floor

---

## 2. The market

### Restaurants

| Who | What they're known for | Where we stand |
|---|---|---|
| **Toast** (USA, the benchmark) | Recipes linked to invoices and stock, plate cost, waste, actual vs expected stock, kitchen screen with wait times, guest profiles joined to reservations, waitlist, online ordering, payroll | We beat them on M-Pesa, offline and phone ordering in Kenya. They beat us on back of house: recipes, food cost and invoices. |
| **Square, Lightspeed, TouchBistro** | Menu management, floor plans, reservations, loyalty, reports, many integrations | Same pattern. Not sold in Kenya with M-Pesa. |
| **SimbaPOS** (Kenya, most named) | Kitchen tickets, tables, bar and kitchen split, add-ons, shifts, M-Pesa. Custom quote; setups said to be KES 80k–150k with hardware | We already do more. They don't advertise ordering from the phone, group orders or splitting across phones. |
| **NomadPOS** (Kenya) | Hospitality only, reservations, staff performance, offline | They have reservations; we don't yet. |
| **Veira** (Kenya) | Free hardware, 1.5% of revenue, eTIMS, offline | Their price model scares bigger restaurants. Our eTIMS is switched off; we offer the owner's own ETR instead. |
| **PawaPOS, Jampos, FoodChow, iOSoft** | Phone POS (KES 1k–3k a month), M-Pesa matching, multi-branch | Narrow. Only iOSoft does multi-branch well. |
| **Odoo** | Everything, with M-Pesa add-ons | Heavy to set up; needs a consultant. |

**What every Kenyan article says a restaurant must have:**
- kitchen tickets
- tables
- M-Pesa without checking the phone by hand
- working offline
- split bills
- staff tracking
- recipe stock
- eTIMS

We have all of these except **recipe stock**, and eTIMS by decision.

**Delivery apps in Kenya:** Glovo (about 33% of diners), Uber Eats (about 21%) and Bolt Food (about 16%), from a 2024
Competition Authority report. Uber has agreed to buy Glovo's owner; the closing date is late 2026 or 2027, depending on the
source. None of them offers small Kenyan restaurants an easy way to connect a POS, so recording their orders by hand on the till
(with each app's commission) is what we'll do first.

### Malls & property

| Who | What they're known for | Where we stand |
|---|---|---|
| **Yardi, MRI** (global malls) | Turnover rent (a % of the tenant's sales above a base), sales returns from tenants, service-charge (CAM) budgets and year-end true-up, rent increases, meters, maintenance, tenant portal | Far ahead of us, and far beyond what a Kenyan mall can pay or set up. |
| **Pickspace, Eurostop** | Turnover rent with breakpoints, sales uploads, collecting sales straight from tenants' tills | The "sales straight from the till" idea is where we can win (section 4). |
| **Nyumbani** (Kenya) | Tenant records, SMS and email invoices and receipts, paybill payments shown at once, arrears with WhatsApp and SMS reminders, visitors, short stays, repairs, reports (arrears, deposit refunds, collections) | The Kenyan bar for residential. No price on the site. |
| **BomaHut, Nyumba Zetu, HomeManager, Ezen, Rent Hero** | Paybill matched to unit by account number, reminders, statements, bank channels | Residential first. **None of the material we found covers malls: no service-charge budgets, no turnover rent, no VAT on commercial rent.** |

**The gap in Kenya:** residential rent apps are plentiful. Software for a small or mid-sized mall, a commercial building or a
market is almost non-existent below Yardi's price. That gap is ours to take.

---

## 3. Each kind of business, and what "personalised" means for it

The rule stays the same: one engine underneath, but each sub-type gets its own first screen, words, questions at signup,
sample data, Home numbers and "Get started" steps.

### Restaurants

| Sub-type | How their day really runs | What must be personal to them |
|---|---|---|
| **Restaurant** (sit-down) | Reservations, waiters on sections, courses fired in order, bills split, tips shared | Reservations and waitlist, sections per waiter, "fire mains", tip sharing, table turn time |
| **Café** | Counter rush, regulars, takeaway cups | Till first (done), regulars' reward (coming soon), order ahead for pickup |
| **Bar & lounge** | Tabs under a name, bottles sold by the tot, nightly count, theft risk | **Tots from bottles**, tabs, nightly bottle count with shortfall in money, happy hour, bouncer or door entry (P3) |
| **Fast food** | Queue, order numbers on a screen, delivery riders, delivery apps | Delivery addresses and own riders, delivery-app orders with commission, combos ("burger meal"), order-number board (done) |
| **Bakery** | Bake at dawn, sell through the day, cake orders with deposits | Daily production (baked, sold, left, wasted), cake orders with pickup date and deposit, ingredients by recipe |
| **Restaurant with rooms** | Meals billed to rooms | Done through rooms; add meal plans (B&B, half board) |
| **Ice-cream & desserts** | Scoops, toppings | Recipes (tub to scoops), counter till |
| **New: Nyama choma & grill** | Meat bought by the kg, sold by the half-kg | Sold by weight, kg-to-portion yield, waste |
| **New: Canteen & caterer** | Staff canteens, school meals, events | Meal tickets or company accounts billed monthly, event quotes (pipeline exists) |
| **New: Food court stall / cloud kitchen** | Several brands from one kitchen, delivery apps | Several menus (brands) in one business, delivery-app channels |

### Malls & property

| Sub-type | How their month really runs | What must be personal to them |
|---|---|---|
| **Mall / shopping centre** | Many shop tenants, a service-charge budget, a food court, common areas, rent increases, VAT invoices, some rent tied to sales | Floor map of units, service-charge budget shared by size and reconciled yearly, yearly increases, **turnover rent from tenants' real sales**, food court, mall-wide marketing |
| **Market stalls** | Traders pay daily or weekly, often cash or M-Pesa to a collector, stalls change hands | **Daily or weekly charges**, a collector's phone screen (walk the rows, mark paid, M-Pesa prompt), defaulters list each evening, stall register |
| **Commercial building** | Office tenants, companies, VAT, withholding tax, utilities, parking | VAT invoices, withholding tax certificates recorded against invoices, sub-meters, parking bays, access cards (P3) |
| **Residential apartments** | Monthly rent by paybill, water bills, deposits, caretaker | Paybill matched to the house number, water meter readings by the caretaker, deposit held and refunded, move-in and move-out checklist, rental income tax |
| **Property agent** | Collects for many landlords, takes a commission, pays out | Landlords as owners of properties, monthly landlord statement with commission and expenses deducted, payouts, one paybill for all |
| **New: Hostel / student housing** | Beds per room, terms not months | Rent per bed, per term |

---

## 4. What we aim for

**The goal in one line:** a Kenyan restaurant, mall, market or landlord should find nothing they need missing, and should find
three or four things no one else offers at any price they can afford.

### 4a. Restaurants: complete

1. **Recipes and ingredient stock (the biggest gap).**
   - A dish lists its ingredients and amounts.
   - Every sale uses them up; prep recipes are allowed (a sauce made once, used in many dishes).
   - Live food cost % per dish.
   - Stock counts show "expected vs counted" in money.
2. **Waste log** with reasons (burnt, expired, dropped, staff meal, returned), in money, per day.
3. **Bar tots.** A bottle sells by the tot, glass or bottle. The nightly count shows the shortfall in tots and in shillings.
4. **Reservations and waitlist.**
   - Book a table from the menu link or WhatsApp, with an optional M-Pesa deposit.
   - Reminders, and a waitlist that sends "your table is ready".
   - Shown on the floor plan.
5. **Delivery.**
   - Saved addresses and our own riders, with a rider's run sheet and cash or M-Pesa on delivery.
   - Glovo, Uber Eats and Bolt Food orders recorded as channels with their commission, so profit is true.
6. **Menu tools:** combos and meal deals, happy-hour prices by time, menu by time of day (breakfast and lunch).
7. **Bakery and cake orders:** daily production (baked, sold, left), cake orders with pickup date, deposit and a reminder.
8. **Paying the right bill** (the open 12 Nov to-do).
   - The phone sees only the bill it placed; anyone else asks the waiter.
   - The waiter confirms "paid, checked" before clearing the table.

### 4b. Restaurants: beyond the market

1. **"Close the night in 3 minutes":** one WhatsApp message to the owner at closing.
   - Sales, M-Pesa vs cash, short or over per cashier.
   - Food cost %, waste, tots short.
   - Voids and discounts, the top dish and the worst dish.
2. **Theft alerts in plain words**, e.g. "Brian voided 3 items after the bill was printed", "Bar is 9 tots of Jameson short
   tonight (KSh 2,700)".
3. **Menu report in plain words:** which dishes make money and sell, which make money but don't sell (promote them), and which
   to drop. Built from recipes plus sales. Toast charges extra for this kind of insight.
4. **Diner WhatsApp list** from people who ordered or paid by phone, with their consent: birthdays and "we miss you" offers.

### 4c. Malls & property: complete

1. **Paybill matched to the unit.**
   - The tenant pays with their unit number as the account (e.g. `G12`).
   - The payment lands on their oldest invoice by itself.
   - Anything that doesn't match goes to an "unmatched" list to assign with one tap.
   - The till-payment API for this already exists.
2. **Tenant statement** (invoices, payments, balance, ageing) as a PDF to email or a WhatsApp link.
3. **Reminders:** before the due date, on it, and after it, by SMS, WhatsApp and email, in the owner's words.
4. **Penalties** for late payment, only where the lease says so (a fixed amount or a % after X days).
5. **Rent increases:** a yearly % or a fixed step on the lease's anniversary, with a notice to the tenant 30 days before.
6. **Deposits:** held per lease and shown as owed back. Move-out deducts repairs and arrears and prints a refund statement.
7. **Meters:** water and electricity readings per unit.
   - The caretaker enters them from a phone, with a photo.
   - The usage is added to the month's invoice at the owner's rate.
8. **Service charge:**
   - A yearly budget by line (security, cleaning, power for common areas, insurance, management).
   - Shared by floor area (or a fixed share).
   - Billed monthly, then reconciled at year end against what was actually spent: a refund or top-up per tenant.
9. **Lease expiry and renewals:** alerts at 90, 60 and 30 days, then renew with new terms in one step.
10. **Daily and weekly charges** for market stalls, with a **collector screen** on the phone.
11. **Maintenance requests:**
    - A tenant reports one from their link, with a photo.
    - The owner assigns it, the cost is recorded, and it can be charged to the tenant or to the service-charge pool.
12. **Tenant page** (a link, no app): statements, pay by M-Pesa prompt, receipts, report a problem, see notices.
13. **Property agents:**
    - Landlords own properties; monthly landlord statements show collected minus commission minus expenses.
    - Payouts are recorded.
14. **Tax:**
    - Residential rental income tax (Monthly Rental Income, 7.5% of gross rent; **rate to confirm with the owner's
      accountant**).
    - VAT on commercial rent for VAT-registered landlords.
    - Withholding tax certificates recorded against invoices.

### 4d. Malls & property: beyond the market

1. **Turnover rent from real sales.** Tenants who use BizNez for their shop can agree (a consent screen) to share their
   monthly sales total, never the details, with their landlord. The landlord's invoice then works out "base rent or X% of
   sales, whichever is higher" by itself. Tenants not on BizNez type their monthly sales into their tenant page instead.
   Pickspace and Eurostop sell this to big malls; nobody in Kenya does it for small ones.
2. **Food court in one QR.**
   - A diner scans one QR and orders from several stalls in one basket.
   - They pay one M-Pesa prompt; each stall's kitchen sees only its own dishes; each stall gets its share.
   - The mall's service fee comes off automatically.
   - This joins the two systems and builds on group orders and kitchen screens, which are built.
   - **The money split needs care:** see decision 4.
3. **Mall directory and offers page.** A public page for the mall (shops, food court, opening hours, today's offers posted by
   tenants), good for Google and for a QR at the entrance.
4. **Floor map:** units drawn on a simple grid per floor, coloured by vacant, let, owes, or lease ending soon. The owner sees
   the whole building at a glance; tapping a unit opens it.
5. **Vacant unit enquiries** go into the existing sales pipeline (Enquiry → Viewing → Offer → Lease signed), with a public
   "units to let" page.
6. **The owner's monthly WhatsApp summary:** billed, collected, % collected, top 5 who owe, leases ending, meters not read.

---

## 5. Build order

Estimates assume one developer (this model) working with tests, as in packages A–I. Each package can be used on its own as
soon as it lands.

### Restaurants

| # | Package | What | About |
|---|---|---|---|
| R1 | Paying the right bill | Phone sees only its own bill; waiter confirms; flag mismatches | 0.5 day |
| R2 | Recipes and ingredients | Recipes and prep recipes, stock used per sale, food cost % per dish, expected vs counted | 2 days |
| R3 | Waste and bar tots | Waste log; bottle → tot/glass; nightly bar count with shortfall in money | 1.5 days |
| R4 | Reservations and waitlist | Booking from a link, deposit, reminders, waitlist, on the floor plan | 1.5 days |
| R5 | Delivery | Addresses, riders, run sheet; delivery-app channels with commission | 1.5 days |
| R6 | Menu tools | Combos, happy hour, menu by time of day | 1 day |
| R7 | Bakery and cake orders, grill by weight | Production log, cake orders, sell by kg with yield; new sub-types (grill, canteen, cloud kitchen) | 1.5 days |
| R8 | Owner's night summary and theft alerts | WhatsApp close-of-day, plain-word alerts, menu report | 1.5 days |

### Malls & property

| # | Package | What | About |
|---|---|---|---|
| M1 | Get paid | Paybill matched by unit number, unmatched list, statements, reminders, penalties | 2 days |
| M2 | The lease's life | Increases, deposits and move-out, expiry alerts and renewals | 1.5 days |
| M3 | Meters and service charge | Meter readings by phone, yearly budget by area, year-end reconciliation | 2 days |
| M4 | Markets | Daily/weekly charges, collector phone screen, defaulters list | 1 day |
| M5 | Tenant page and maintenance | Tenant link (statement, pay, report a problem, notices) and maintenance requests | 1.5 days |
| M6 | Agents and tax | Landlords, landlord statements, commission, payouts; rental income tax, VAT and WHT | 1.5 days |
| M7 | Floor map and "units to let" | Grid map per floor, public vacant-units page into the pipeline | 1 day |
| M8 | Mall extras | Turnover rent (with tenant consent), mall directory and offers page | 2 days |
| M9 | Food court in one QR | One basket from many stalls, one payment, each stall's share | 2.5 days |

**Total:** about 11 days for restaurants and 15 days for malls and property, so roughly 5 working weeks for everything.

**Against the 22 November go-live (9 days away):** not everything fits. The suggestion:
- **Before 22 Nov:** R1, R2, R3 (the waiting restaurant client needs them most) and M1, M2 (without these a landlord can't
  really use it), about 7.5 days.
- **After go-live:** everything else, in the order above. The remaining journey items (phase 3–5) wait or are squeezed in.

---

## 6. Decisions for the owner

1. **Order against go-live:** the split in section 5 (R1–R3 and M1–M2 before 22 Nov), or a different mix?
2. **The journey work** (phase 3 fixes, Get started checklist): pause it until after these, or keep doing it alongside?
3. **Which plan gets which features?**
   - Suggested: recipes and bar tots in Growth and up; service charge, meters, agents and turnover rent in Growth and up.
   - Lite landlords get units, leases, paybill matching, statements and reminders.
4. **Food court money:** one paybill can't split money automatically. Either the mall collects and pays each stall (the mall
   holds tenants' money, so it is a trust question), or each stall's own M-Pesa is prompted separately (several prompts,
   clumsier). Suggested: the mall collects, and BizNez shows each stall what it's owed every day. **This must be checked so
   neither LeziTech nor BizNez ever holds money.**
5. **Turnover rent:** happy for tenants on BizNez to share their monthly sales total with their landlord, only with their
   consent? It is the strongest reason for a mall to bring its tenants onto BizNez.
6. **Rental income tax rate and withholding tax rules:** confirm with an accountant before the Tax page shows them.
7. **New sub-types:** add nyama choma & grill, canteen & caterer, cloud kitchen, and hostel? Each gets its own words and
   sample data.

---

Sources used for section 2: Toast's back-of-house and front-of-house pages (pos.toasttab.com), Capterra and Sonary reviews of
Toast; the Veira-written "best restaurant POS Kenya 2026" guide on dev.to (SimbaPOS, NomadPOS, Jampos, iOSoft, PawaPOS);
nyumbani.ke and its comparison with BomaHut and Nyumba Zetu; the HomeManager M-Pesa write-up on dev.to; Pickspace,
Eurostop and Kolonell pages on turnover rent and service-charge reconciliation; Hapa Kenya, TechCabal and Capital FM on
Uber's purchase of Glovo and delivery-app shares.
