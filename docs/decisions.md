# BizNez: owner decisions

The owner's answers to open questions, newest first. When a plan document disagrees with this file, this file wins.
Add new answers at the top with the date; don't rewrite old ones (strike them through if they change).

## 2026-11-11 (later): restaurant QR ordering

- **SEO pages go to the second model** (prompt: `docs/prompt-seo-pages.md`). This session does the restaurant QR
  ordering and the till changes. Build plan: `docs/plan-restaurant-and-tills.md`.
- **Everything in the QR plan is needed before go-live**: the waiting client wants all of it, including group orders,
  photos and allergens, kitchen substitutes, loyalty points, board screens per area and tips.
- **When diners pay: the restaurant chooses** in its settings: "pay when the food is ready" (default, as planned) or
  "pay before it goes to the kitchen".
- **QR codes open the app's address** (e.g. `suite.lezitech.site/menu/{restaurant}?table=5`), where the menu and
  tracking pages already live. Not `lezitech.site/s/...`.
- **Files (menu photos, uploaded ETRs) on Cloudflare R2 from day one.** The owner creates the R2 bucket and keys;
  local development uses a disk folder behind the same interface.

## 2026-11-11: before go-live

Context: the other model's plans (`platform-enhancements.md`, `restaurant-table-ordering.md`) raised questions;
these are the owner's answers.

### Name and look
- The product is **BizNez** (renamed from Lezi Suite). LeziTech stays the company; domains and repo names unchanged.
- The mark is an ink **B** on lime with the coral dot.

### Order of work before go-live (22 Nov 2026)
1. **SEO pages** on the website (sector, sub-sector and town pages, search markup, sitemap, `noindex` on the app).
2. **Restaurant QR ordering** (scan at the table, order, follow, pay by phone). **A client is waiting for it, so it
   ships before go-live.** Keep its documentation current as it is built: update `restaurant-table-ordering.md`
   with what is built, what changed from the plan and what is left, in the same commit as the code.
3. **Drawers:** blind count, required float, owner reconciliation with day lock.
4. **Buy by the carton, sell by the piece** (pack and loose units).
5. **Customer statements** and what customers owe.
6. **Test server and deploy last, at go-live**: Contabo/Coolify access, Brevo key and DNS, and the PayHero sandbox
   payment come from the owner then.

### Plans and prices
- **Lite: KSh 2,650 a month, 3 staff**, each with their own cash drawer. Growth KSh 4,500 and Pro KSh 9,000 unchanged.
  Yearly = 10 × monthly. (The app still says KSh 2,000 / 2 staff: change `PlanLimits` and `SettingsController` plans.)

### Cash drawers and end of day
- **Blind count:** a cashier closing a shift types what they counted and is not shown the expected amount. Only the
  owner and manager see expected vs counted and the difference.
- **Float required:** a cashier must enter opening cash before their first sale.
- **Lock after reconciling:** when the owner reconciles a cashier's day, that day's sales by that cashier are locked
  (no voids, refunds or edits). The owner can unlock; unlocking is recorded in the activity log.
- **Cashiers see their own shifts** (float, sales, what they counted) but not the expected amount or the difference.

### Website and ads
- Features not built yet (carton/piece units, customer statements) are shown as **"coming soon", with a note that we
  are building them now**. Never present them as available.
- Only claim what is built for everything else.

### M-Pesa set-up
- Show both ways to connect, **PayHero first** (ready in minutes, a fee per payment), Daraja second (no middleman
  fee, slower Safaricom approval). Typing the till number by hand keeps working from day one.
- The PayHero fee table in `platform-enhancements.md` is **unverified** (its "you receive" column is wrong). Confirm the
  rates with PayHero before showing any fee figures to customers.

### Tax receipts (ETR) and tax due
- eTIMS integration is hard to get in Kenya, so BizNez must work without it:
  - the business generates the ETR on its own KRA device or portal, **uploads it (photo or PDF) against the sale**,
    and BizNez **emails it to the customer**;
  - **free**, part of the plan (no KSh 4 per ETR; this overrides the QR plan);
  - the Tax page shows **tax due per week, per month or for any dates**, whether or not eTIMS is connected.
- **Turnover Tax rate: 1.5%** (current rate); the owner can change it in settings if KRA changes it.

### Support access
- LeziTech staff get full admin in a business **only when the owner switches it on**: 5 hours, the owner can stop it
  any time, and everything done is recorded in the business's activity log. (Overrides the QR plan's version.)

### Sectors
- **Beauty clinic stays live** (treatment notes, not medical records). Pharmacy and medical clinics stay "coming soon".
