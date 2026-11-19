# Plan: launch quality first, then deploy and market

Written 2026-11-19, updated the same day with the market research (`market-research-2026-11-19.md`). Status: **approved, to be built in its own session.**

## Progress

| Phase | Status | Notes |
|---|---|---|
| A. Phone pass | ✅ Done 2026-11-19 | Findings A.1–A.15 and screenshots in `journey-findings.md` and `docs/screens/phase-a/` |
| B. Speed | Next | |
| C. Reliability | Waiting on accounts for the money and email parts | |
| D. Finish what a trial touches | | |
| E. Paying moment, referrals, mall offer | | |
| G. Growth tools | | |
| F. Sign-off and deploy | Waiting on the owner's go-ahead | |

## The decision

**A short, fixed quality pass (about 10–12 working days), then deploy and market to the five strong business types.**
The weaker types keep improving after launch (`plan-gaps.md` phases 4–10). Each one is opened to marketing when its phase ships.

**Why this order:**
- Income comes from converting trials. Google search in Kenya gives only about 300 ad clicks a month for these needs
  (about KSh 18,000; see the research), so every trial must count, and most growth must come from other channels
  (the website's own Google ranking, field agents, malls and referrals), which need their tools ready at launch.
  A slow first load, a confusing phone screen or a failed payment loses that customer for good.
- Waiting for every sector (wholesale, services, tours) would delay income by weeks.
  Real users also show what matters faster than more building: the phase-2 walk found 2 blockers in an afternoon.

**Launch to these five:**
- restaurants & cafés;
- shops & minimarts (except phone shops, petrol stations and gas & water);
- salons & barbers (except gyms);
- hotels & lodges;
- malls, landlords & property.

**Before deploying:**
- Wholesale and tours stay on the website, but no ads.
- Service types whose screens don't yet do their work (garage, laundry, school, tailoring), gyms, phone shops, petrol stations and gas & water are marked "coming soon", like pharmacy, until their phase ships.

## The rule for every change: easy on a phone first

Every screen touched in this plan is checked at **360 px and 412 px wide** (common Android phones) before it's called done:
- **Thumbs:**
  - the main action is a full-width labelled button within thumb reach (bottom of the screen or a bottom sheet);
  - tap targets are at least 44 px;
  - there's no horizontal scrolling;
  - tables become cards on a phone.
- **Words:**
  - every action is a labelled button, a verb plus what it acts on ("Add a product", "Send the receipt");
  - no vague links or clickable cards;
  - plain words with no jargon (no "due/owe/folio/SKU");
  - a tickbox says what happens when ticked.
- **Short:**
  - one job per screen;
  - forms ask only what's needed now, and the rest comes later or is optional (KRA PIN is always optional);
  - long pages get short sections with a jump list.
- **Feedback:**
  - every wait shows a loading sign;
  - every save says it's saved;
  - every error says what to do next.
- **Clean start:** samples are off by default; a trial starts empty, guided by the first-day checklist.

## Phase A: Phone pass on the five lead types (2–3 days)

For each lead type, walk as the **owner** and as their main **staff role** (cashier, waiter, stylist, front desk, collector):
sign-up → first-day checklist → first real sale, booking, stay or rent bill → paid → receipt → end of day.
Fix everything that breaks the rule above. Known items to fix along the way:
- **2.10** Settings: short sections with a jump list; M-Pesa near the top.
- **2.7** "Open your till" in the first-day checklist, so "Start the day" isn't a surprise.
- **2.11** "What do you want to use?" worded per type (a café: "Regulars and their orders").
- **2.13** Sales leads on a phone: "Tap a card to move it on"; no dragging needed.
- **1.14** Signup step 2: a loading sign instead of a blank screen.
- The big screens built since 12 Nov, which haven't been walked on a phone yet:
  - malls (floor maps, tenant portal, collectors, food court);
  - restaurant R1–R8 (delivery, combos, bakery, waste, bookings);
  - variants, packs and stock-take;
  - branches and several businesses per owner;
  - expenses and profit & loss;
  - online booking (salons, hotels).

**Done when:** each lead type's owner and staff paths work at 360 px with no dead end, no unlabelled action and no horizontal scroll.
Screenshots of each go into `journey-findings.md`.

## Phase B: Speed (1 day)

- **Split the app download by screen.** Today it's one 1.4 MB file (374 KB compressed) before anything shows.
  Target: the first screen under about 150 KB compressed, the rest loading as needed.
  First load on a slow phone should drop from about 3–6 seconds to 1–2.
- **A year of data:** seed one busy business (a café with about 50,000 sales and a mall with 200 units and 2 years of rent).
  Time every list and report; add database indexes or paging where anything takes over 1 second.
- **Photos:** made small on upload (already done for menu photos; check products, logos and the mall directory).
- **Screens that refresh** (kitchen, order board, tables): check how often they ask the server, and back off when the tab is hidden.

**Done when:**
- the first screen loads in under about 2 seconds on a throttled "Fast 3G" phone;
- no screen with a year of data takes over 1 second.

## Phase C: Reliability (1–2 days)

- **Money paths end to end** with PayHero (and Daraja if a business uses it), in sandbox and then a real KSh 10:
  - a prompt that's paid, refused, timed out, and paid twice;
  - the callback arriving late;
  - the business's own PayHero charge.
  Every case must leave the bill right.
- **Email:** Brevo with the real domain; codes, receipts, statements and reminders land in Gmail's inbox (not spam), branded.
- **Errors you hear about:** any server error emails LeziTech once (grouped, not one email per error), and a simple uptime check pings the API every minute.
- **Backups:** nightly off-server, plus **one restore test** before launch.
- **Safety:** wrong-PIN and wrong-code limits, the hand-over code and public pages (menu, booking, statements) can't be guessed or abused; quick check of the security headers.
- **Offline till:** sell with Wi-Fi off, come back online; nothing lost and nothing doubled.

**Done when:** every money case leaves the bill right, a restore has worked once, and a test error reaches your email.

## Phase D: Finish what a trial touches (1–2 days)

Website (from `journey-findings.md`):
- **1.6** Pricing matches the current plans by tools (check; the old "one part only" rule was dropped on 16 Nov).
- **1.7** "Bluetooth receipt printer": check on a phone; otherwise say "USB, or Bluetooth through your phone's print settings".
- **1.10** "See it working" on each business-type page, opening that type's demo.
- **1.12** Restaurants page shows what's new: QR ordering, paying from the phone, group orders, delivery, kitchen and TV screens.
- **1.13** Wholesale sample screen shows only what the app does.
- **1.17** Yearly saving on pricing.
- **1.16** Unique "A day at your…" text for the 20 top sub-types (SEO and trust).
- **0.2** Google Ads doc corrected before any ad: `businez.com`, real addresses, only built features.

App:
- Weak types marked "coming soon" (see the decision above), with "Tell me when it's ready" (the setup form) instead of Start free.
- The **reach-us button** in the app and on the website: a compact icon opening WhatsApp (0796814154), call, and feedback.
- Halls: a simple day view of hall bookings (hotel 🟡 item), if it fits; otherwise after launch.

## Phase E: The paying moment, referrals and the mall-tenant offer (2 days)

The trial must turn into money without help:
- the countdown from the first day (days left, what they keep, what it costs for their tools);
- one-tap M-Pesa payment on a phone, the receipt by email, and the plan unlocking at once;
- an expired trial keeps everything readable and says plainly how to continue;
- reminder emails at 3 days, 1 day and the last day, in the business's words;
- your offers from the control panel show correctly on the plan page.

Two growth offers (owner's go-ahead, 19 Nov):
- **Referral: a free month for both.**
  - Every business gets its own "Invite a business" link and a WhatsApp share button, in the app and on the paid receipt email.
  - When the invited business pays its first month, both get a free month added (the `?ref=` code is already recorded at signup).
  - A small "Your invites" list shows who joined and who has paid.
  - Plain words throughout ("Your friend gets a free month when they pay. So do you.").
- **Mall-tenant discount.**
  - A landlord on BizNez can turn on "Offer my tenants BizNez at a discount" (for example 20% off, set in the control panel per mall).
  - Tenants see the offer on their tenant portal and statements, with a Start free button that carries the mall's code.
  - Signups through it get the discount automatically and show in the control panel as coming from that mall.
  - The landlord's own BizNez bill can get a thank-you credit per tenant who pays (LeziTech's choice, set in the control panel).

**Done when:**
- a test business goes from trial to paid on a phone in under a minute, with a real KSh payment, refunded afterwards;
- a referred business paying adds a free month to both;
- a mall tenant's signup gets the discount and shows its mall.

## Phase G: Growth tools (2 days)

The research's best case depends on channels beyond ads; each needs a tool and a number from day one.
- **Google Ads tracking:**
  - a "trial started" conversion on the app's signup (already sends `StartTrial`; wire the Google tag);
  - a **"paid" conversion sent back to Google** (offline conversion import, using the `gclid` already recorded at signup), so Google learns to find businesses that pay;
  - one campaign with 5 ad groups (restaurants, shops, salons, hotels, property), each pointing to its business-type page.
    The negative keyword list starts from the research: jobs, course, pdf, free download, crack, project, student, card machine.
- **The website's Google ranking:**
  - Search Console set up for `businez.com`, with the sitemap submitted;
  - each business-type and town page checked for title, description and speed on a phone.
- **Agents' kit** (for 1–2 field agents selling face to face):
  - an agent role in the control panel, with their own referral code and link;
  - a demo business per lead type they can open on their phone;
  - signups and paid businesses counted per agent, and their commission worked out (the rate set by LeziTech);
  - a one-page "how to show BizNez in 5 minutes" guide per lead type.
- **One results page** in the control panel (build on "Ad results"):
  - trials, paying businesses and leavers by channel (Google ads, website ranking, agent, mall, referral, direct) and by business type;
  - cost per paying business where spend is known.

**Done when:**
- a test signup from an ad, an agent code, a mall link and a referral each shows under the right channel;
- a test payment reaches Google Ads as a "paid" conversion.

## Phase H: KRA eTIMS (decision now, build after launch)

Competitors (Uzapoint) issue KRA eTIMS invoices on every sale, and shops that must issue them will ask.
BizNez already has the eTIMS connection point (`EtimsClient`, with a stub), plus ETR upload and tax with or without eTIMS.
- **Owner's decision now:**
  1. apply to KRA as an eTIMS third-party integrator (slow, months); or
  2. connect through an already-approved eTIMS integrator's API (faster; a fee per invoice or per month).
- **Build after launch:** the real client behind `EtimsClient` for the chosen route, tested in KRA's sandbox.
  It's switched on per business that needs it, and shown on the shops page once live.
- Until then the website says plainly: "Tax receipts: upload your ETR and we send it with the receipt. Direct KRA eTIMS: coming soon."

## Phase F: Sign-off and deploy (1 day)

1. Walk every lead type again on a phone, owner and staff, with samples off, as a new customer would. One screenshot set each.
2. All tests pass; the app and website typecheck clean; build sizes recorded.
3. Deploy by `ops/go-live.md` on the GEE M'S server, with BizNez's own memory and processor limits
   (API ~1.5 GB, its database ~1 GB) and night-time deploys.
   Move to its own VPS at about 50 paying businesses or at the first sign of GEE M'S slowing.
4. Launch-day phone checks (runbook section 11) on the live site, then start ads for the five lead types only.

## After launch (while earning)

- Weekly: watch errors, slow requests, server memory and trial-to-paid numbers per type.
- `plan-gaps.md` phases 4–10, in this order:
  - job cards (garage, laundry, repairs);
  - wholesale delivery notes and price levels;
  - shop specials (phones, petrol, gas);
  - tours and car hire;
  - schools and gyms;
  - the thin types;
  - SMS and WhatsApp.
  Each opens its types to marketing when it ships.
- Then the roadmap: custom platforms for the restaurant and mall clients, and the public API with keys.

## Timing

| Phase | Days (about) |
|---|---|
| A. Phone pass, five lead types | 2–3 |
| B. Speed | 1 |
| C. Reliability | 1–2 |
| D. Finish what a trial touches | 1–2 |
| E. The paying moment, referrals and mall-tenant offer | 2 |
| G. Growth tools | 2 |
| F. Sign-off and deploy | 1 |
| **Total** | **10–12 working days** |
| H. KRA eTIMS | decision now; build after launch |

Started now, that means deploying around **the end of November or the first days of December**.
Order: A → B → C → D → E → G → F. Phases A and D can run side by side, because they touch different files
(app screens vs website), and so can B and G.

## Needed from the owner

- **Go-ahead** on this plan, and on marking the weak types "coming soon" until their phase ships.
- **Before phase C:** a PayHero sandbox or the live LeziTech PayHero account; Brevo with the `businez.com` domain; Backblaze B2.
- **Before phase G:**
  - the Google Ads account to run BizNez ads in (a new account under the LeziTech Agency manager);
  - Search Console access for `businez.com`;
  - the agents' commission rate.
- **Phase H:** the eTIMS route (apply to KRA, or use an approved integrator).
- **Before phase F:** DNS access for `businez.com` and `lezitech.site`, and a Coolify API token saved to a file.
