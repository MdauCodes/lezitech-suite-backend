# Sector study: what each kind of business needs from BizNez

Written 2026-11-11 for the LeziTech team. For every sector: the business types we cover, how they actually run a day,
what **selling (POS)**, **customers (CRM)** and **back office (ERP)** must mean for them, what we have, and what's
missing. Status: **Have**, **Partly** or **Gap**. Priority: **P1** (blocks selling to that business), **P2** (they'll
ask within weeks), **P3** (nice to have).

The big lesson: the same word means different work. "CRM" for a restaurant is regulars, loyalty and bookings; for a
packaging supplier it is leads, quotations, account statements and reorder reminders. "Inventory" for a minimart is
counting finished goods; for a restaurant it is ingredients used by recipes; for a butchery it is kilograms and
yield. The platform should keep one engine but show each business the version that fits.

## The kinds of inventory (one engine, several shapes)

| Shape | Who | What it needs | Status |
|---|---|---|---|
| Finished goods | Minimart, liquor, bookshop, cosmetics | Count units, reorder levels, stock takes, shrinkage | Have |
| Pack and loose | Wholesale, hardware, minimart, agrovet | Buy by carton or bag, sell by piece, kg or metre, automatic conversion | **Gap (P1)** |
| Batch and expiry | Pharmacy, agrovet, FMCG | Batches, first-expiry-first-out, alerts | Have |
| Ingredients by recipe | Restaurant, café, bar, bakery, fast food | Selling a dish uses up its ingredients; waste log; food cost % | **Gap (P1)** |
| Bar measures | Bars, lounges, hotels | A bottle sold by the tot or glass | **Gap (P2)** |
| By weight | Butchery, produce, bakery | Sell by kg (scale weight typed or read), carcass to cuts yield | Partly (decimal qty works; no yield) |
| Size and colour | Boutique, shoes, auto spares | One product with many variants, each with its own stock | **Gap (P1 for fashion)** |
| Serial numbers | Electronics, phones | Each unit's IMEI or serial, warranty, returns by serial | **Gap (P2)** |
| Returnables | Gas refill, water, liquor crates | Empty cylinders and crates in and out, deposits | **Gap (P2)** |
| Used per job | Salon (back-bar), car wash, garage, printing | Products used while serving, not sold over the counter | **Gap (P2)** |
| Made to order | Packaging, printing, furniture, tailoring, manufacturer | Job or work order, materials reserved, stages, delivery | **Gap (P1 for these)** |
| Places, not goods | Hotels (rooms), tours (seats), property (units) | Availability, not stock | Have |

## Restaurants and cafés
Types: restaurant, café, bar and lounge, fast food, bakery, restaurant with rooms, ice cream and desserts.

How the day runs: open, prep (ingredients out of the store), service rushes with tables and takeaway, kitchen tickets,
bills split and settled, bar tabs, close with a cash count and waste noted.

- **POS:** tables, kitchen screen and tickets, courses, add-ons, split bills, tips, takeaway and delivery, QR menu,
  order-ready board: **Have**. Void and price-change reasons recorded: **now part of the activity log**. Happy-hour
  prices: **Gap (P3)**. Bar tabs held under a name: Partly (open bills).
- **CRM (guests, not leads):** regulars and loyalty points **Have**; table reservations **Gap (P2)**; birthdays and
  WhatsApp offers **Gap (P3)**; delivery addresses **Gap (P2)**. A sales-leads pipeline makes little sense here
  except for events and catering, so keep CRM off by default (it is).
- **ERP:** recipes so dishes use up ingredients **Gap (P1)**; waste log with reasons **Gap (P2)**; food-cost % per
  dish **Gap (P2, follows recipes)**; bakery daily production (made, sold, left over) **Gap (P2)**; bar measures
  **Gap (P2)**.

## Hotels, lodges and guest houses
Types: hotel, safari lodge or camp, guest house or B&B, serviced apartments, Airbnb host, conference and events venue.

- **POS:** room bill, restaurant charge-to-room, deposits **Have**.
- **Rooms:** room chart, check-in and out, housekeeping **Have**; rates by season and meal plan (bed and breakfast,
  half board, full board) **Gap (P1 for lodges)**; group bookings **Gap (P2)**; sync with Booking.com or Airbnb
  **Gap (P2)**; conference rooms booked by the hour or day **Gap (P1 for venues)**.
- **CRM:** guest history and preferences **Partly**; company accounts billed monthly **Partly (pay later + limit)**;
  event and group enquiries as leads with quotes **Have (pipeline + quotes)**.
- **ERP:** minibar and housekeeping supplies with par levels per room **Gap (P3)**; food and drink as restaurants.

## Shops (retail)
Types: minimart, boutique and fashion, electronics and phones, hardware, agrovet, bookshop, liquor, butchery, auto
spares, petrol station, gas and water refill.

- **POS:** fast barcode till, cash, M-Pesa, card, pay later **Have**; floor price so cashiers can bargain only down to
  the lowest acceptable price **now built**; returns and exchanges against the original sale **Partly (refunds)**;
  layaway (pay in parts, collect later) **Partly (deposits)**; weighed items **Partly**.
- **CRM:** loyalty and credit accounts **Have**; customer statements and debt reminders **Gap (P1 for hardware,
  agrovet, auto spares)**; WhatsApp or SMS offers **Gap (P3)**.
- **ERP by type:**
  - minimart: pack and loose **Gap (P1)**, stock takes **Have**, supplier credit **Partly**;
  - boutique: sizes and colours **Gap (P1)**, markdowns **Gap (P3)**;
  - electronics: serial and IMEI with warranty **Gap (P2)**;
  - hardware and building materials: metres, bags and pieces **Gap (P1)**, quotations **Have**;
  - agrovet: batches and expiry **Have**;
  - liquor: daily counts against theft **Have (stock count)**, crates **Gap (P2)**;
  - butchery: kg **Partly**, carcass yield **Gap (P2)**;
  - auto spares: part numbers and fits-which-car **Gap (P3)**;
  - petrol station: pump readings and tank dips per shift **Gap (P2, own module)**;
  - gas refill: empty-cylinder exchange **Gap (P2)**.

## Salons, barbers and beauty
Types: hair salon, barbershop, spa, nail bar, cosmetics shop, beauty clinic, car wash, gym. (Medical and dental
clinic: coming soon.)

- **POS:** services and products together, who did the work, commissions, tips **Have**.
- **Bookings:** diary per stylist **Have**; reminders by WhatsApp or SMS the day before **Gap (P2)**; deposits for
  no-shows **Partly**.
- **CRM (clients):** visit history **Partly**; notes such as colour formulas **Partly (customer notes)**; rebooking
  reminders **Gap (P2)**; packages and memberships (gym, spa) **Gap (P1 for gyms)**.
- **ERP:** products used while serving (back-bar) separate from retail stock **Gap (P2)**; car wash queue by number
  plate **Gap (P3)**.

## Wholesale and distribution
Types: packaging supplier, FMCG wholesaler, distributor, manufacturer, building materials.

How the day runs: customer orders by phone or WhatsApp, quotation, invoice on credit up to a limit, picking and
delivery, collections, restocking from suppliers.

- **POS (invoicing):** price tiers, quotations to invoices, credit limits **Have**; floor price **now built**;
  delivery notes and partial deliveries **Gap (P1)**; customer purchase order (LPO) number on the invoice **Gap (P2)**;
  A4 invoices **Have**.
- **CRM (business accounts):** leads, follow-ups and quotations **Have**; customer statements and ageing (30, 60, 90
  days) **Gap (P1)**; reorder reminders ("they buy every 2 weeks") **Gap (P2)**; sales reps, routes and visit logs
  **Gap (P2)**.
- **ERP:** pack and loose (carton, bale, pallet) **Gap (P1)**; several stores **Have**; supplier payables **Partly**;
  backorders **Gap (P2)**; manufacturer: raw materials into finished goods (production) **Gap (P1 for
  manufacturers)**; packaging supplier: custom jobs (size, print, minimum order, lead time) as work orders **Gap
  (P1 for printed packaging)**.

## Pharmacies (coming soon, health data)
Batches, expiry and first-expiry-first-out **Have**; prescription warning **Have**; controlled-drug register and
insurance claims **Gap**. Stays "coming soon" until the data-protection question is settled.

## Service businesses
Types: agency, consultancy, real estate, training and schools, logistics, cleaning and pest control, repairs and
installations, laundry, garage, furniture, tailoring, printing and branding.

- **POS (invoicing):** quotations, invoices, deposits **Have**.
- **CRM is the heart:** leads, pipeline, follow-ups **Have**; recurring contracts with a visit schedule (cleaning,
  pest control) **Gap (P1 for them)**; customer statements **Gap (P1)**.
- **Jobs:** job cards with stages (received, in progress, ready, collected) for garage, repairs, laundry, tailoring,
  printing, furniture **Gap (P1 for these)**; laundry tags per item **Gap (P2)**; parts and materials used per job
  **Gap (P2)**; schools: students, terms and fee statements **Gap (separate module, P3)**.

## Safaris and tours
Trips, seats, deposits and balances **Have**; itineraries in quotations **Partly**; costs per trip (park fees,
lodges, fuel) to see margin **Gap (P2)**; vehicles and drivers allocated to trips (car hire) **Gap (P2)**.

## Malls, landlords and property
Units, leases, monthly rent bills, deposits, arrears **Have**; service charge **Partly**; water meter readings and
billing **Gap (P2)**; tenant statements **Gap (P1)**; maintenance requests **Gap (P3)**; market stalls paid daily
**Gap (P2)**.

## Across every sector

| Need | Status | Priority |
|---|---|---|
| Activity log per business (price changes, below-price sales, voids, refunds, discounts, per staff, flagged) | **Built now** | P1 |
| Floor price: retail price and lowest acceptable price per item | **Built now** | P1 |
| Branded receipts and invoices, emailed in the background, problems shown to the business and to LeziTech | **Built now** | P1 |
| Customer statements and ageing, debt reminders | Gap | P1 |
| Pack and loose units | Gap | P1 |
| Job cards (work orders) | Gap | P1 for service and made-to-order |
| Recipes (ingredients used by what is sold) | Gap | P1 for food |
| Sizes and colours | Gap | P1 for fashion |
| Expenses and profit and loss | Gap | P2 |
| Reminders by WhatsApp or SMS (bookings, rent, reorders, debts) | Gap | P2 |
| Memberships and recurring billing | Gap | P2 (P1 gyms, contracts) |
| Delivery notes | Gap | P1 for wholesale |
| Branch-limited staff | Gap | P2 |

## Suggested build order (after go-live essentials)

1. Customer statements and ageing, with WhatsApp debt reminders (wholesale, hardware, services, property).
2. Pack and loose units (wholesale, retail, hardware, agrovet).
3. Job cards (garage, repairs, laundry, printing, tailoring, packaging jobs).
4. Recipes and waste (restaurants, bakeries, bars).
5. Sizes and colours (fashion), delivery notes (wholesale), expenses and profit and loss (everyone).
6. Reminders and memberships, then the sector-specific modules (petrol, gas exchange, water billing, schools).

Each of these should switch on by default only for the business types that need it, and stay available to any
other business in Settings.
