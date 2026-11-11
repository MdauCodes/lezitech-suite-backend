# How Lezi Suite is organised (decisions, 2026-11-11)

Rule of thumb: **the default is what suits most clients; every client can change it.**

## POS, CRM and ERP: one connected system

- **Default:** one system. One list of items, one list of customers, one set of sales; nothing is typed twice.
  The menu is grouped by the day's tasks (Today, Sell, Stock & people, Money, Business), not by product name,
  because small business owners don't think in "ERP" or "CRM".
- **Option:** Settings, "What you use": Selling (POS), Customers & sales leads (CRM), Back office (ERP). Switching a
  part off removes its screens from the menu. Home, customers, items, reports, staff and settings always stay.
  Stored in `tenants.products`; screens per part are in `lib/roles.ts` (`PRODUCT_MODULES`).
- **Plans:** Lite = one part, Growth = any two, Pro = all three. Enforced when saving settings (`PLAN_LIMIT`).

## Reports

Three tabs, each shown only if the business uses that part:
- **Till & sales (POS):** totals, VAT, profit, what sold, payment methods, by staff, category, channel, day by
  day, commissions. `GET /api/reports/sales`.
- **Back office (ERP):** stock value (cost and selling price), running low, bought and from whom, stock written
  off, who owes you, who you owe, profit by category. `GET /api/reports/back-office`.
- **Customers & leads (CRM):** who bought, new and returning customers, walk-in sales, best customers, leads won,
  lost and open, why leads were lost. `GET /api/reports/customers`.
- **Tax** stays its own page. Quick ranges: today, 7 days, this month, 30 days. Each tab downloads for Excel.

## Who can do what

- **Isolation between businesses:** Postgres row-level security on every business table (`tenant_fence` on
  `app.tenant`), set for each request. One business can never read another's data.
- **Default jobs:** Owner, Manager, Cashier, Waiter, Kitchen, Sales, Stylist, Front desk, Accounts, each with
  sensible screens (`lib/roles.ts`) and server rules (`RoleGuard`).
- **Option (owner only):** Settings, "Who can do what" (Advanced). Per job: which screens it sees, whether it may
  give refunds, and whether it may give discounts or change prices without a manager's PIN. Stored in
  `settings.access`; enforced on the server by `AccessPolicy` (a removed screen is closed, not just hidden).
  Staff, billing and settings can't be handed out; the owner always keeps everything; managers keep Staff.
- **Later:** limiting a person to one branch.

## Receipts and printing

- **Default:** 80mm receipt, printed when the cashier presses Print, logo shown.
- **Options:** 58mm, 80mm or A4; print automatically after each sale; logo on or off; kitchen tickets when orders
  are sent. Credit sales, invoices and quotations can always print as an A4 invoice. Printing goes through the
  browser, so any printer the device can already use works. Reprint any receipt from Sales.
- **Later:** direct printing without the print dialog (WebUSB / Web Bluetooth ESC/POS), label printing.

## Custom work

Clients can ask LeziTech to build a website, a custom system, a mobile app, a connection or branding: on the
website (`/custom`) or in the app (Settings, "Need something built?"). Both land under control panel Requests,
filter "Custom work" (`setup_requests.kind = 'CUSTOM'`).

## Devices

Built for phones, tablets, laptops and desktops: menus collapse on phones, the till has a phone layout, tables
scroll inside their card. Reports, Settings, the till and the website were checked at phone width (375px).

## Rule that applies everywhere

A customer must never sense the platform is shared: "your business", never "each business" or "workspace".
