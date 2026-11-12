# Plan: customer journeys and "Get started"

Written 2026-11-12. Status: **plan, waiting for the owner's go-ahead**. Runs alongside the go-live preparation (server, Brevo, R2,
PayHero), so the launch date doesn't wait on it.

**Goal:** every target owner (restaurant, shop, salon, hotel, wholesale, services, tours, property) can go from a Google search
to their first real sale, booking or bill without help, and knows at each step what they get and where to click. Plain words, no
technical terms, nothing promised that isn't built.

---

## Who we walk as

One owner per open business type, on a phone first (most will be), then on a laptop. Pharmacy and medical clinics stay
"coming soon": we only check that their path says so clearly and offers the "set it up for me" form.

| Owner | Arrives from (a likely search) | First page | The first real thing they must get done | "It works" moment |
|---|---|---|---|---|
| Restaurant / café | "restaurant POS Kenya M-Pesa" | Restaurants page, then e.g. Café sub-page | Their menu in, a table order sent to the kitchen | Kitchen sees it; the bill is paid by M-Pesa |
| Shop / minimart | "shop POS Nairobi" | Retail page or a town page | Their items in (Excel or scan), a sale rung up | Receipt printed or sent; stock goes down |
| Salon / barber | "salon booking software Kenya" | Beauty page | Services and stylists in, a booking taken | Checkout with the stylist's commission |
| Hotel / lodge | "hotel management system Kenya" | Hotels page | Rooms in, a guest checked in | Restaurant bill put on the room; check-out |
| Wholesale / distributor | "wholesale invoicing Kenya" | Wholesale page | Price tiers and a credit customer | A quotation turned into an invoice; what they owe |
| Services / agency | "quotation and invoice app Kenya" | Services page | A client and a quotation | Invoice sent; payment recorded |
| Tours / safaris | "safari booking system" | Tours page | A trip with seats | A booking with a deposit; the manifest |
| Property / rentals | "rent collection app Kenya" | Property page | Units and tenants | Rent bills raised; a payment matched |

The exact pages, wording and first actions are confirmed in phase 0 from `sectors.json`, `subsectors.json` and the website.

---

## Phase 0: get ready (about 2 hours)

1. **The website's SEO work** (uncommitted, from the other developer): typecheck it, look through it, and commit it, so we walk
   what will actually go live. Anything that looks wrong is noted, not silently changed.
2. Run all three together: API (8090), app (5320), website (5340). Point the website at the local app and API.
3. Set up the findings log: `docs/journey-findings.md`, one section per owner, one line per finding:
   **where** (page and step), **what happened**, **how bad** (blocker / confusing / polish), **fix**, **status**.
4. Agree the "how bad" scale:
   - **Blocker:** a dead end, a wrong link, an error, a button that does nothing, a promise the app doesn't keep.
   - **Confusing:** the owner would hesitate or need to ask (jargon, unclear next step, two similar buttons).
   - **Polish:** works, but could be clearer or nicer.

## Phase 1: website to signup (about 3 hours)

For each owner, on a phone-sized screen, then a laptop:

1. **Landing page:** does it say in the first screen, in plain words, what they get, what it costs (KSh 2,650 Lite), and that it
   works with M-Pesa and without internet? Is every feature named actually built for that business type?
2. **Sub-type and town pages:** same checks, plus: is it clearly for *them* (a café, not "a restaurant business")?
3. **Every button:** "Start free", "Get set up", "See a demo", "Pricing", the header and footer links. Each must land on the
   right place, with their business type already chosen and the ad tags kept.
4. **"Set it up for me" form:** sent, arrives in the control panel under Setup requests, and the page says what happens next and when.
5. **Demo:** the right demo business opens, in the right role, and it's obvious how to get back to sign up.
6. **Coming soon (pharmacy, clinic):** says so, and offers the setup form instead of a signup that would fail.

Output: phase 1 findings in the log.

## Phase 2: signup to "it works" in the app (about 4 hours)

For each owner:

1. **Signup:** fields, questions for their sub-type, the 6-digit code, errors in plain words, how long it takes.
2. **The first screen:** do they understand what they're looking at (sample data vs their own), and what to do first?
3. **Their first real thing** (table above): put their own items, services, rooms, units or menu in, then do the first sale,
   booking or bill all the way to paid.
4. **Getting paid:** M-Pesa setup (Daraja or PayHero) is findable and understandable; till and cash drawer; receipts by WhatsApp,
   email and print.
5. **Bringing staff in:** adding a cashier or waiter, and what that person sees when they sign in.
6. **Trial and paying:** the trial banner, the reminders, Billing, plan limits, paying for the plan by M-Pesa.
7. **Clearing out the sample data**, so their own records start clean (check this is possible and clear).

Output: phase 2 findings in the log, with a screenshot for every blocker.

## Phase 3: fix what we found (about 4 to 6 hours, depends on findings)

1. **Blockers first,** all of them, in both repos (website and app), each with a test where it's code.
2. **Confusing next:** wording, button labels, empty screens that don't say what to do, missing "next step" links.
3. **Polish** only if time allows; the rest goes to a list for after launch.
4. Anything that needs your decision (a price, a promise on the website, removing a feature from a page) is put to you,
   not guessed.

## Phase 4: "Get started" checklist in the app (about 4 hours)

A short list on Home for the owner (and the manager), tailored to their business type. Each step ticks itself when it's
actually done (from their data, not a click), links straight to the screen that does it, and the list can be hidden once
finished.

- **Every business:** add your business details and logo · set up M-Pesa · add a staff member · make your first real sale.
- **Plus, per type** (examples, final list from phase 2):
  - Restaurant: your menu with prices · your tables and QR cards · send a table order to the kitchen.
  - Shop: your items (Excel import or barcode scan) · opening stock.
  - Salon: services with times · stylists and their commission · take a booking.
  - Hotel: rooms and rates · check a guest in.
  - Wholesale: price tiers · a customer on credit · a quotation.
  - Services: a client · a quotation, then an invoice.
  - Tours: a trip with seats · a booking with a deposit.
  - Property: units · tenants · this month's rent bills.
- Built as: `GET /api/onboarding/checklist` (steps and done/not-done worked out on the server from the data, per business type)
  and a Home card with progress; sample records don't count as done. Tests for each business type.

## Phase 5: walk again and sign off (about 2 hours)

1. Walk every owner's path again after the fixes, phone first.
2. One screenshot set per owner (landing → signup → first screen → checklist → the "it works" moment) in the findings log.
3. Update `HANDOFF.md`, commit, push.
4. A short summary for you: what was found, what was fixed, what's left for after launch.

---

## Timing and order

| Phase | Time (about) | Can start |
|---|---|---|
| 0. Get ready | 2 h | On your word |
| 1. Website to signup | 3 h | After 0 |
| 2. Signup to "it works" | 4 h | After 0 (alongside 1 is possible) |
| 3. Fix | 4–6 h | As findings come in |
| 4. Get started checklist | 4 h | After 2 (needs the per-type steps) |
| 5. Walk again, sign off | 2 h | After 3 and 4 |

About **2 working days** in all, finishing well before 22 November, while you gather the go-live items (Contabo + Coolify,
domains and DNS, R2 keys, Brevo, PayHero sandbox).

## Decisions for the owner (can be answered as we go)

1. **Sample data:** keep it as is, or add a clear "Remove sample data" button once they've added their own? (Recommended: add the
   button, and say on the first screen that it's sample data.)
2. **Website promises:** when a page names something we don't do for that type, we remove it from the page (recommended) rather
   than build it now.
3. **Who sees the checklist:** owner and manager only (recommended), or everyone.
