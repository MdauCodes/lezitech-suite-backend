# Plan: close the audit gaps, phase by phase (biggest reach first)

## Context
The 17 Nov audit (`J:\backend\lezitech-suite-api\docs\audit-2026-11-17.md`) scored the platform per target customer.
- Restaurants and property: 9/10. Shops: 7. Hotels, salons, wholesale: 5. Services and tours: 4.
- Many business types carry their own words ("Job card", "Fee invoice", "Member") with no workflow behind them.
- Ordinary businesses can't see profit.

This plan closes those gaps in **10 phases, built one at a time**: each is shipped, tested and reviewed by the owner before the next starts.

Owner decisions, 17 Nov:
- Reminders go by **email for now**, behind a channel-neutral sender so Africa's Talking (SMS or WhatsApp, whichever the business picks) plugs in later.
- Hotels sync with Airbnb and Booking.com by **iCal both ways**.
- Order: **biggest reach first**.

## How every phase is built (same rules as before)
- **Migrations:** one per phase, starting at **V58** (last is V57).
  - Every new business table gets the `tenant_fence` block copied from V38.
  - `RlsCoverageTest` must stay green.
- **API:**
  - business logic in a service;
  - controller under `/api/...`;
  - `RoleGuard` rules for the new paths;
  - `PlanFeatures.CATALOG` entry when it's a paid tool. Public pages call `planFeatures.has(tenant, key)` themselves, because PlanGuard skips `/api/public/**`.
- **Public pages:** copy the table-booking pattern.
  - `ReservationController.business(slug)` + `as(b, work)` (`TenantContext.runAs` + `TransactionTemplate`);
  - an `RateLimit` per tenant+IP;
  - a token per booking (`replace(gen_random_uuid()::text,'-','')`);
  - a branded page in `pages/public/`, a guest "my booking" page;
  - deposits via an M-Pesa event (like `ReservationDepositSettled`).
- **Emails:** `Mailer.trySendAs(tenant, …)` with `EmailLayout`. **Background jobs:** `@Scheduled`, looping over tenants with `runAs` + try/catch, like `ReservationController.jobs()`.
- **Screens:**
  - the business's own words (`terms`), plain labelled buttons, phone-first;
  - shown only when the sector's `features`/`modules` say so (`useSession().features`, `has()`);
  - plan tools through `usePlanTools()`.
- **Each phase ends with:**
  - API tests for its rules;
  - full suite green, web and website typecheck clean;
  - browser check in the matching demo business (desktop and 375px);
  - docs: tick the phase in `docs/plan-gaps.md` (new; this plan copied into the repo) and update the audit score;
  - commit as MdauCodes (no trailer), push `launch` = `main`.

---

## Phase 1: Business expenses and profit & loss (every business)
- **V58:**
  - `expenses` table: date, category, what, amount, how paid (CASH/MPESA/BANK/CARD), paid to, receipt photo (FileService purpose RECEIPT), branch, `paid_from_till` + `shift_id`, created_by.
  - `order_lines.unit_cost`, captured when lines are added, so profit uses the cost at the time of sale. Old lines fall back to `items.cost`.
- **API:**
  - `restaurant`-independent `finance/ExpenseService` and `ExpenseController`: CRUD; owner, manager and accountant add or edit; cashiers may record "paid from the till".
  - Default categories per sector, editable in settings: rent, salaries and wages, electricity, water, transport, airtime and data, supplies, repairs, marketing, licences, other.
  - Cash paid from the till lowers the shift's expected cash (`ShiftController` summary).
- **Report:** `GET /api/reports/profit-loss?from&to` in `reports/ReportController`, reusing `window()` and the SOLD filter:
  - sales excluding VAT;
  - cost of goods: `unit_cost`, or recipe portion cost for restaurants via `Recipes.unitCosts()` / `NightSummary.portionCost`;
  - gross profit;
  - expenses by category; waste and write-offs (`waste_entries`, ADJUST moves); commissions paid; M-Pesa/PayHero fees (existing `mpesaFees`);
  - **net profit**, compared with the previous period.
- **Plan tool:** `profitLoss` in `PlanFeatures`. Expenses themselves stay on every plan.
- **Screens:**
  - **Expenses** page (Money group: list, add with photo, by category, month total);
  - **Profit & loss** tab in Reports (plain words: "What came in, what it cost, what you kept");
  - expenses line in the night summary;
  - "Pay from the till" on the till bar.
- **Tests:** `ExpensesAndProfitTest`:
  - net profit maths;
  - cost snapshot survives a price change;
  - till cash expected drops;
  - privacy;
  - plan gating.

## Phase 2: Salons, barbers & beauty
- **Reminder service** (channel-neutral, built here, used by later phases):
  - V59 `reminders` (ref_kind, ref_id, kind, channel EMAIL|SMS|WHATSAPP, sent_to, status, error, sent_at), unique (ref_kind, ref_id, kind, channel), the same claim pattern as `rent_reminders`.
  - `notify/Reminders` with a `Channel` interface. Only `EmailChannel` now; `SmsChannel` and `WhatsAppChannel` come in Phase 10.
  - Business setting `reminders.channel` (email now) and the hours before (default 24 h and 2 h).
- **Appointments:** V59 adds token, source (WALK_IN/PHONE/ONLINE), email, reminded_at, deposit fields.
  - The scheduled job emails clients before their appointment, with a branded email and "Can't make it? Cancel here".
- **Public booking page:** a salon's link `/book/{slug}`. The same link as restaurants; the page picks the salon flow when the business has `appointments`.
  - The client chooses a service, then a stylist or "anyone", then a day, then a free time (from staff hours and existing bookings), then name, phone and email.
  - Optional **deposit** by M-Pesa prompt (setting).
  - "My booking" page with cancel or rebook.
- **API:** `GET/POST /api/public/appointments/{slug}`, `/a/{token}`.
  - Staff working hours: `users.work_hours` jsonb, a default week.
  - The existing clash check in `BookingController.book` is reused.
- **Screens:**
  - Bookings gets "Share your booking link", online bookings marked, a new-booking ring (reuse `BookingRing`), and "Send reminder now".
  - Staff hours on the Staff screen.
- **Tests:** `OnlineAppointmentsTest`:
  - free slots respect hours and clashes;
  - a booking can't be double-taken;
  - reminder sent once;
  - token cancel;
  - deposit holds the slot;
  - rate limit;
  - privacy.

## Phase 3: Hotels, lodges & guest houses
- **iCal import:**
  - V60 `room_calendars` (space_id, source name, url, last_synced_at, last_error) and `room_blocks` (space_id, starts_on, ends_on, uid, source).
  - A job every 15 minutes fetches each URL through a new hardened `files/SafeFetch`. It is extracted from `PhotoLinks.fetch`, adds blocks for 100.64/10, 0/8 and IPv4-mapped IPv6, connects to the checked IP, and limits size and content type.
  - It parses VEVENTs and replaces that calendar's blocks.
  - `StayService.assertFree` and `/rooms/available` respect blocks; the chart shows them as "Booked on Airbnb".
- **iCal export:** each room gets `GET /api/public/ical/{slug}/{spaceId}/{token}.ics` with its RESERVED and IN_HOUSE stays (no guest names), plus a "Copy link for Airbnb/Booking.com" on the room.
- **Online room booking page** `/stay/{slug}`:
  - dates, then room types with price for the stay, then guest details;
  - optional deposit by M-Pesa;
  - the booking lands as RESERVED with source ONLINE, and the front desk gets a ring;
  - confirmation email plus a "my stay" page.
- **Guest register:** V60 stays add id_type, id_number, nationality, coming_from, vehicle_reg. Asked at check-in, required if the hotel switches it on. There is a printable register for a date range.
- **Seasonal and weekend rates:** V60 `room_rates` (room type or room, from, to, weekdays, rate, min nights). The stay price is the sum of each night's rate, shown before saving.
- **Conference and halls:** halls booked by day through stays with event name, organiser, people and package. The hall gets its own simple day calendar.
- **Tests:** `RoomCalendarsTest`:
  - a blocked night refuses a booking;
  - the export lists stays without names;
  - unsafe URLs are refused;
  - seasonal price sum;
  - online booking;
  - register required.

## Phase 4: Job cards (garage, laundry, repairs, tailoring, printing, car wash)
- Generalise the property maintenance flow (`property/portal/Maintenance`, `JobsController`, `pages/Jobs.tsx`) into business jobs.
  - V61 `work_jobs`: number, kind, customer, title, details, `fields` jsonb, status, assigned_to, promised_on, order_id, token, rating.
  - `work_job_updates`: message, photos, status.
- **Per-kind steps and fields** (from the sector pack, e.g. `jobs.steps`, `jobs.fields`):
  - **Garage:** vehicle reg, make and model, mileage. Received, Checking, Waiting for parts, Working, Ready, Collected.
  - **Laundry:** items and tags, express. Received, Washing, Ironing, Ready, Collected.
  - **Tailoring:** measurements, fitting date. Received, Cutting, Fitting, Ready, Collected.
  - **Repairs and printing:** device or job details, proof approved.
  - **Car wash:** reg number and wash type. Queue, Washing, Ready, Collected.
- The job's quote or invoice is the existing order. Parts and labour lines come from the till, and payment happens on collection.
- The customer gets a **status link** (token page) and a "Ready for collection" email, by SMS later.
- **Screens:**
  - a **Jobs board** with columns per step, in the business's words ("Job cards", "Tickets", "Washes");
  - a job page with timeline and photos;
  - "Ready" and "Collected" buttons;
  - collected-but-unpaid warnings.
- **Tests:** `WorkJobsTest`:
  - steps follow the kind;
  - invoice link;
  - ready email once;
  - customer page shows no internal notes;
  - privacy.

## Phase 5: Wholesale & distribution
- **Price tiers:** V62 `price_lists` and `price_list_items` (item, price, min qty); `customers.price_list_id`.
  - `OrderService.addLines` picks the customer's list price, else the wholesale price, else the shelf price.
  - The same order of choice runs on the till (`Pos.tsx lineTotal`) and on quotes.
  - Excel columns per list.
- **Delivery notes:**
  - V62 `deliveries` and `delivery_lines`: from an invoice, with partial deliveries, driver, vehicle, delivered-to name, and a signature or photo;
  - printable note;
  - "not yet delivered" list.
- **Rep routes and van sales:**
  - customers get a route and visit day, and reps get "My route today";
  - a van is a stock location (location kind VAN) filled by transfer;
  - rep sales come from the van stock, with sales by rep.
- **Manufacturer production runs:** reuse `Recipes` (`saveRecipe`, `made`).
  - Allow a finished PRODUCT to have a recipe made from ingredients and batches.
  - A production log records what was made, materials used, cost per unit and who made it.
- **Tests:** `WholesaleTest`:
  - list price wins over wholesale;
  - partial delivery leaves a balance;
  - van stock moves;
  - production cost.

## Phase 6: Shop specials
- **Phones and electronics:**
  - V63 `item_serials` (item, serial/IMEI, status, purchase ref, sale line, warranty months);
  - `items.track_serials`;
  - the till asks for the IMEI;
  - the receipt shows the serial and warranty end;
  - "Find by IMEI" for warranty claims and returns.
- **Petrol stations:**
  - pumps and nozzles (fuel item);
  - attendant shift meter readings at open and close, giving litres sold;
  - reconcile against till sales and cash;
  - tank dips with variance;
  - price changes logged.
- **Gas and water:**
  - cylinder sizes;
  - "Refill (exchange)" versus "New with cylinder", where the cylinder deposit is a separate returnable line;
  - empties counted in stock;
  - water refills by litre.
- **Liquor:**
  - `items.age_restricted` makes the till confirm "Customer is over 18" (logged);
  - an optional licensed-hours setting refuses sales outside them without a manager PIN.
- **Tests:** `ShopSpecialsTest`:
  - IMEI sold once, and its warranty date;
  - meter litres versus sales;
  - deposit returned on an empty;
  - age check logged.

## Phase 7: Tours & car hire
- **Itineraries:** a day-by-day plan per package (V64 `package_days`), plus included and excluded lists, a shareable branded itinerary page and PDF print.
- **Trip costs:** `trip_costs` (supplier, what, per person or fixed, amount) gives **profit per trip** on the trip card and in reports.
- **Car hire:**
  - V64 `vehicles` (reg, make, seats, daily rate, status) and `rentals` (vehicle, customer, driver's licence no., out and in date-time, km out and in, fuel, deposit, damage notes and photos, extra days and late fee);
  - an availability calendar;
  - the rental's invoice is an order.
  - The `tours/car-hire` type gets the Vehicles and Rentals screens instead of Trips.
- **Tests:** `ToursAndCarHireTest`:
  - trip profit;
  - a vehicle can't be double-rented;
  - late return fee;
  - km logged.

## Phase 8: Schools and gyms
- **Schools:**
  - V65 `classes`, students (customers with class, guardian name and phone), `terms`, `fee_structures` (class, term, items);
  - **bill a whole class for the term in one step**: one invoice per student;
  - fee statements reuse `customer_ledger` and statement links;
  - balances by class, with term reminder emails.
- **Gyms:**
  - V65 `membership_plans` (name, days, price) and `memberships` (customer, plan, starts, ends, status, frozen days);
  - door check-in by search or QR card;
  - expiring and expired lists;
  - renewal reminders through the Phase 2 reminder service;
  - renewal as a sale.
- **Tests:** `SchoolsAndGymsTest`:
  - class billing makes one invoice each;
  - statement balance;
  - expired member refused at the door with "Renew";
  - freeze extends the end date.

## Phase 9: Thin business types
- Fill the **39 types with no seed** (list from the exploration: restaurant-rooms, the hotel types, the 4 pharmacy types (kept coming soon), wholesale ×5, services ×6, tours ×5, property ×5, …).
- Each gets its own seed categories and items, terms where they differ, kpis, 1–3 setup questions and examples, in `subsectors.json`.
- Types that are their sector's default (restaurant, hotel, minimart, hair-salon) take the sector seed on purpose and are marked so.
- A new check in `SubSectorTest`: every open type has examples, kpis and either its own seed or the "uses sector seed" mark. Each type's sign-up is tried once in the demo.

## Phase 10: SMS and WhatsApp via Africa's Talking (when the account is ready)
- `notify/AfricasTalking.send` (SMS) and WhatsApp through Africa's Talking, as the `SmsChannel` and `WhatsAppChannel` in `Reminders`.
- The business picks email, SMS or WhatsApp in settings.
- Usage is counted per business, with a delivery log.
- **Needs from the owner:** an Africa's Talking account, sender ID and WhatsApp number approval.

## Not in this plan
Pharmacy stays "coming soon" (batches and the drugs register come later). Delivery-app integrations (Glovo, Uber Eats) and payroll are after go-live.

## Verification (each phase)
1. `./mvnw -q test` passes everything, including the phase's new test class and `RlsCoverageTest`. Don't run it while the `suite-api` preview is starting.
2. `npx tsc -b` in the app and `npx tsc --noEmit` in `website/`.
3. Preview `suite-api` + `suite-web`; sign in to the matching demo (`/demo`); walk the new flow on desktop and at 375px. Public pages are tried signed out.
4. Docs updated (`docs/plan-gaps.md`, audit score), commit, push, and an owner review before the next phase.

---

## After these phases (owner's roadmap, 17 Nov)
1. **Separate custom platforms for two clients** before going online:
   - one for managing a **restaurant**, one for a **mall**;
   - each separated and fully customised, then deployed so the clients get a link.
2. **Customer side per client:** BizNez's own customer pages stay limited. A fuller website or customer side is built per client as custom work.
3. **Public API for clients who already have a website:**
   - documented endpoints;
   - **API keys** per business (create, revoke, scopes);
   - developer documentation.

Status of the phases above is ticked here as each one ships.
