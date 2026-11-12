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

Website page → app sector. The website's page addresses are plural (`lezi-site.tsx`, `sectors`); its "kind" names for links
are in `lezi-app.ts` (`APP_SECTOR`):

| Owner | Website page | Kind (links) | App sector | First real thing to get done | "It works" moment |
|---|---|---|---|---|---|
| Restaurant / café | `/sectors/restaurants` | `restaurant` | `restaurant` | Menu in; a table order sent to the kitchen | Kitchen sees it; bill paid by M-Pesa |
| Shop / minimart | `/sectors/shops` | `shop` | `retail` | Items in (Excel or scan); a sale | Receipt; stock goes down |
| Salon / barber | `/sectors/salons` | `salon` | `beauty` | Services and stylists in; a booking | Checkout with commission |
| Hotel / lodge | `/sectors/hotels` | `hotel` | `hotel` | Rooms in; a guest checked in | Restaurant bill on the room; check-out |
| Wholesale | `/sectors/wholesale` | `wholesale` | `wholesale` | Price tiers; a credit customer | Quotation → invoice; what they owe |
| Services / agency | `/sectors/services` | `agency` | `services` | A client and a quotation | Invoice; payment recorded |
| Tours / safaris | `/sectors/safaris` | `safari` | `tours` | A trip with seats | Booking with a deposit; manifest |
| Property | `/sectors/property` | `property` | `property` | Units and tenants | Rent bills; a payment matched |
| Pharmacy (coming soon) | `/sectors/pharmacies` | `pharmacy` | `pharmacy` | — | Says "coming soon", offers the setup form |

Town pages (`/towns/{town}`, 10 towns) are walked once, as an extra way in, not per owner.

## Findings

### Found while preparing (phase 0)

| # | Where | What happened | How bad | Fix | Status |
|---|---|---|---|---|---|
| 0.1 | Website "Start free" from a sub-type page (e.g. Café) | Only the business type is passed to signup (`?sector=restaurant`), not the sub-type, so the owner picks "Café" again | Confusing | Pass the sub-type too, and have signup pre-select it | Fixed: "Start free" opens a picker (kind of business, then closest sub-type) and signup opens with both (`?sector=restaurant&sub=cafe`); sub-type pages link straight there |
| 0.2 | `docs/google-ads-strategy.md` | Uses `biznez.com`; three of its addresses don't exist (`/sectors/retail`, `/sectors/services`, `/sectors/tours`: the real ones are `shop`, `agency`, `safari`) and nor does `/compare`; promises unbuilt features ("Pack & Loose", "Customer Statements", "From KSh 1,000 first month") | Blocker (for ads) | Correct before any ad goes live: `businez.com`, the real addresses, only built features | Open |

### Phase 1: website to signup (done 2026-11-12, phone size first)

**What works:** the home page says in the first screen what it does, "Start free for 7 days", "No card needed" and "or let us set
it up"; every feature it names is built (offline till, M-Pesa prompts, stock, credit, tax, staff logins). The 9 business-type pages
name only built features, apart from 1.13. Pricing matches the app exactly (KSh 2,650 / 4,500 / 9,000 with the right logins,
branches and items). "Start free" carries the business type and the ad tags (`utm_*`, `gclid`) to signup, and signup step 2
opens with the type chosen, its sub-types and plain questions. The "Let us set it up" form is short, answers "We'll call or
WhatsApp you soon", and the request reached the control panel with the right type, town and source. Town pages read well. The
phone menu works.

| # | Where | What happened | How bad | Fix | Status |
|---|---|---|---|---|---|
| 1.1 | All 59 sub-type pages (`/sectors/{type}/{sub}`) | They never show: each displays its parent page (Hardware shows Shops, Café shows Restaurants); only the tab title changes. Cause: `sectors.$sector.tsx` has no `<Outlet/>` for the child route. To Google: 59 copies of 9 pages | Blocker | Make the sub-type route stand alone (not nested), then review the real sub-type page | Fixed: route `sectors_.$sector.$sub.tsx` stands alone; checked Hardware, Café |
| 1.2 | `sub-sector-page.tsx` (shows once 1.1 is fixed) | "Start free" passes the app's name (`retail`, `beauty`, `services`, `tours`) where the link expects the website's (`shop`, `salon`, `agency`, `safari`), so the business type is lost and a wrong value is saved in the visitor's browser (spoiling other pages' links); "What it does" lists raw setting names | Blocker | Pass the website kind; list plain feature words | Fixed: links carry the website kind (Hardware → `?sector=retail`); plain feature words; bad saved values ignored |
| 1.3 | Pharmacy page, its 4 sub-types, and Medical & dental clinic | Say "Start free as a pharmacy / salon". In the app, pharmacy lands on step 1 with no explanation (it's further down as "coming soon"). Breaks the health-data rule on the public site | Blocker | Website: "Coming soon" + the setup form, no Start free. App: a clear message when a coming-soon type arrives | Fixed: pharmacy and its sub-types, and Medical & dental clinic say "coming soon" with "Tell me when it's ready" (setup form, pharmacy pre-picked), `noindex`; app signup says "Pharmacies & chemists aren't open yet" with a link |
| 1.4 | `/sitemap.xml` (and `/api/sitemap.xml`) | 404: the sitemap file isn't registered as a route; `robots.txt` points Google at it. It also lists the pharmacy pages | Blocker (SEO) | Serve it at `/sitemap.xml` as a proper server route; leave out coming-soon pages | Fixed: `routes/sitemap[.]xml.ts`, 88 addresses, no coming-soon pages; also fixed doubled `</url>` tags |
| 1.5 | Wrong addresses, e.g. `/sectors/shop`, `/sectors/hotel`, `/sectors/pharmacy` | Show the Restaurants page instead of "not found" | Confusing (and SEO) | A real "not found" page with links to the business types | Fixed: unknown business types and sub-types return 404 |
| 1.6 | Pricing | Doesn't say Lite covers one part only (selling, or customers, or back office), which the app enforces | Confusing | Say it on the Lite card ("One part: selling, customers or back office") | Open |
| 1.7 | What you need | Offers "Receipt printer… USB or Bluetooth"; the handoff lists Bluetooth printing as not built | Confusing (promise) | Check on a phone; if not, say "USB, or Bluetooth through your phone's print settings" or drop Bluetooth | Open |
| 1.8 | Town pages | Promise "Setup visits available… We cover Mombasa, Kilifi and Kwale" (and likewise for 10 towns) | Owner decision | Owner: can visits really happen? If not, say "set up remotely; visits in Nairobi" | Fixed (owner chose): town pages say set up by phone and WhatsApp; visits in Nairobi only |
| 1.9 | Footer and Contact | Public email is a personal Gmail (`mdaucodes@gmail.com`); two different numbers for WhatsApp and calls | Owner decision | A business address (e.g. `hello@businez.com`) once the domain is live; confirm the numbers | Decided: keep the Gmail until the domain and mailbox exist |
| 1.10 | Whole website | Never offers the one-click demo, which exists per business type in the app | Confusing (missed chance) | "See it working" on each business-type page, opening that type's demo | Open |
| 1.11 | Sub-type addresses and titles | "Café" becomes `/sectors/restaurants/caf`; tab titles are lower case ("caf POS Kenya") | Polish | Strip accents properly (é → e); title-case the titles | Fixed: Café is `/sectors/restaurants/cafe`; titles use the real name ("Café POS in Kenya") |
| 1.12 | Restaurants page | Undersells what's new and different: QR ordering from the table, paying from the phone, group orders, tips, loyalty, TV order screens | Polish (sales) | Add these to the restaurant page | Open |
| 1.13 | Wholesale page sample screen | Shows three price levels (Retail / Wholesale / Distributor) and cartons and bales; the app has two levels and no carton/piece selling | Confusing (promise) | Show two levels and plain units | Open |
| 1.14 | App signup, step 2 | Blank for about 2 seconds before the form appears (no loading sign) | Polish | Show a loading sign | Open |
| 1.15 | "Let us set it up" form | No pharmacy in the business list (the coming-soon owner we send there must pick "Something else") | Polish | Add "Pharmacy or chemist (coming soon)" | Fixed |
| 1.16 | Sub-type pages (once 1.1 is fixed) | The "A day at your…" text is the same for all 20 top sub-types | Polish (SEO) | Write the planned unique copy, 150–300 words each | Open |
| 1.17 | Pricing | The yearly saving ("2 months free") isn't shown; the app has it | Polish | Add a Monthly / Yearly switch or a line | Open |

### Also done after phase 2 (owner's answers, 2026-11-12)

- Signup from the website arrives filled in: the details the visitor gave in the website's sheet pass through the API under
  a one-time code (read once, 30 minutes), so no name or phone is ever in a link.

### Phase 2: signup to "it works" (done 2026-11-12, phone size)

**How:** the café owner was walked end to end in the app on a phone, from the website sheet (`/start?sector=restaurant&sub=cafe`):
signup's 4 steps, first screen, Home, opening the till, a takeaway sale paid by M-Pesa (simulated), the receipt, Settings
(M-Pesa, printing, loyalty) and adding staff. The other 7 types were signed up through the same signup request the form uses,
then walked from their first screen to their main job: shop (till, items, Excel), salon (booking → take payment), hotel
(arrivals → check in), property (units, rent bills), tours (trips), services (sales leads), wholesale (its menu and numbers).

**What works:** signup is 4 short steps in plain words and knows the sub-type (Café pre-picked, café questions, café sample
menu, café words). Every type lands on a screen made for it, with a "How this screen works" note and sample records that look
like a real Kenyan business. Opening the till, a sale, an M-Pesa prompt and the receipt (WhatsApp, email, print, KRA receipt)
all work on a phone. Without M-Pesa set up, the till says plainly where to set it up and to type the M-Pesa code for now.
M-Pesa set-up (Daraja or PayHero) is under Settings, Payments, and explained without jargon. Each type has its own words
(Tenants and Charges, Clients and Services, Packages and extras) and its own Home numbers.

| # | Where | What happened | How bad | Fix | Status |
|---|---|---|---|---|---|
| 2.1 | After signup (every type) | The owner lands on their work screen (a café on Tables, a salon on Bookings…), so they never see Home's "Get started in 4 steps" card. Nothing says what to do first | Blocker | Land a new owner on Home; phase 4 makes the steps tick themselves | Fixed: signup now opens Home |
| 2.2 | Receipts, email receipts, A4 invoices | Every receipt said "incl. VAT 71.72" and bills were titled "TAX INVOICE", for every business. Most small businesses aren't VAT-registered (Turnover Tax); showing VAT they don't charge is wrong on a customer's receipt | Blocker (legal) | Show VAT and "Tax invoice" only when the Tax page says VAT-registered | Fixed (app and email; test added) |
| 2.3 | Add staff | A cashier or waiter needs an email and a password (the database requires an email). Many staff have no email, so the owner must invent addresses | Blocker (for real use) | PIN-only staff: name + job + PIN, no email; they sign in on the branded staff page. Needs a small schema change | Fixed: name + job + PIN is enough; email optional (lets them reset their own PIN by code); owner/manager can always set a new PIN; staff sign in on the staff page with their name and PIN |
| 2.4 | Café first screen | A café ("a fast counter till for coffee, snacks and regulars") works from a dining-room table plan (12 tables, Main hall, Terrace). Counter cafés should start on the till | Confusing | Café, fast food, bakery and ice-cream: the till first, tables optional | Fixed (owner chose): signup asks café, fast food, bakery and ice-cream "How do customers order?"; "At the counter" hides Tables and opens the till |
| 2.5 | Signup step 2, café | "Do you give regulars a free cup after a number of visits?" Yes changes nothing: loyalty stays off ("It's off until you set it up") | Confusing (promise) | If Yes, add "Set up your regulars' reward" to Get started (loyalty is points-based, not a stamp card) | Fixed (owner chose): the question now says a free cup for regulars is coming soon and asks if they would use it |
| 2.6 | Sample data (every type) | No way to remove the samples, and they aren't marked, so they mix with the owner's own items, guests and sales. Property samples already "owe" KES 234,000 | Confusing (owner decision 1) | A "Remove sample data" button once they've added their own; say on Home that it's sample data | Fixed (owner chose): once they add their own item, customer, room or a sale, the app asks "Remove the samples?" (Not yet = ask in 3 days); old sales keep the item names |
| 2.7 | Till, first payment | Before the first payment the owner must "Start the day" (count the cash drawer). Clear, but unexpected the first time | Polish | Mention it in Get started ("Open your till") | Open (phase 4) |
| 2.8 | Hotel, after check-in | A 2-night guest shows "KES 0 due" right after check-in (nights are charged one at a time), so the room looks free | Confusing | Show the stay's total and what's charged so far ("KES 0 of 9,000 so far") | Fixed: "2 nights × KES 9,500 = KES 19,000 (added at check-out) · Food & extras · Paid so far · To pay at check-out" |
| 2.9 | Trips | A trip with 0 of 7 seats booked said "Fully paid" | Polish | "No bookings yet" | Fixed |
| 2.10 | Settings (phone) | One very long page; Payments (M-Pesa) is halfway down, after printing and screen links | Polish | Short sections with a jump list at the top, M-Pesa near the top | Open |
| 2.11 | Signup step 2, "What do you want to use?" | "Customers & sales leads… people who may buy" reads oddly for a café or a shop | Polish | Word it per type (for a café: "Regulars and their orders") | Open |
| 2.12 | Signup | No email check at signup (no 6-digit code), so a mistyped email isn't noticed until the password reset fails | Polish (owner decision) | Optional: confirm the email with a code during the trial, not at signup | Fixed (owner chose): Home asks the owner to confirm their email with a 6-digit code during the trial; a password or PIN reset code also confirms it |
| 2.13 | Sales leads (phone) | "Drag a card to the next column": dragging is hard on a phone, though each card also has stage buttons | Polish | On a phone say "Tap a card to move it on" | Open |
