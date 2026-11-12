# Customer journey findings

The log for `plan-customer-journeys.md`. One line per finding: **where**, **what happened**, **how bad**, **fix**, **status**.
How bad: **Blocker** (dead end, wrong link, error, a promise the app doesn't keep) · **Confusing** (the owner would hesitate or
need to ask) · **Polish** (works, could be clearer).

Phase 0 done 2026-11-12:
- The website's SEO pages (the other developer's work) typecheck clean and are committed (web `c93099e`); every canonical link
  uses `businez.com`.
- Local run: API 8090, app 5320, website 5340 (preview config `suite-website` added to `J:\backend\.claude\launch.json`). The
  website's "Start free" goes to `APP/start?sector=…` with the ad tags; "Get set up" posts to `/api/public/setup-requests`.

## The paths we walk

Website address → app sector (from `website/src/lib/lezi-app.ts`, `APP_SECTOR`):

| Owner | Website pages | App sector | First real thing to get done | "It works" moment |
|---|---|---|---|---|
| Restaurant / café | `/sectors/restaurant`, e.g. `/sectors/restaurant/{sub}` | `restaurant` | Menu in; a table order sent to the kitchen | Kitchen sees it; bill paid by M-Pesa |
| Shop / minimart | `/sectors/shop` | `retail` | Items in (Excel or scan); a sale | Receipt; stock goes down |
| Salon / barber | `/sectors/salon` | `beauty` | Services and stylists in; a booking | Checkout with commission |
| Hotel / lodge | `/sectors/hotel` | `hotel` | Rooms in; a guest checked in | Restaurant bill on the room; check-out |
| Wholesale | `/sectors/wholesale` | `wholesale` | Price tiers; a credit customer | Quotation → invoice; what they owe |
| Services / agency | `/sectors/agency` | `services` | A client and a quotation | Invoice; payment recorded |
| Tours / safaris | `/sectors/safari` | `tours` | A trip with seats | Booking with a deposit; manifest |
| Property | `/sectors/property` | `property` | Units and tenants | Rent bills; a payment matched |
| Pharmacy (coming soon) | `/sectors/pharmacy` | `pharmacy` | — | Says "coming soon", offers the setup form |

Town pages (`/towns/{town}`, 10 towns) are walked once, as an extra way in, not per owner.

## Findings

### Found while preparing (phase 0)

| # | Where | What happened | How bad | Fix | Status |
|---|---|---|---|---|---|
| 0.1 | Website "Start free" from a sub-type page (e.g. Café) | Only the business type is passed to signup (`?sector=restaurant`), not the sub-type, so the owner picks "Café" again | Confusing | Pass the sub-type too, and have signup pre-select it | Open |
| 0.2 | `docs/google-ads-strategy.md` | Uses `biznez.com`; three of its addresses don't exist (`/sectors/retail`, `/sectors/services`, `/sectors/tours`: the real ones are `shop`, `agency`, `safari`) and nor does `/compare`; promises unbuilt features ("Pack & Loose", "Customer Statements", "From KSh 1,000 first month") | Blocker (for ads) | Correct before any ad goes live: `businez.com`, the real addresses, only built features | Open |

### Phase 1: website to signup

_(to be filled)_

### Phase 2: signup to "it works"

_(to be filled)_
