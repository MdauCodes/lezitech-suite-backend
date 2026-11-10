# Lezi Suite: handoff for the next session

Read this first. It says where everything is, what is decided, what is built, what isn't, and what to do next.
Last updated: 2026-11-10. Go-live target: **22 November 2026** on Contabo + Coolify.

## 1. What Lezi Suite is

A multi-sector POS, stock, M-Pesa, CRM and tax app for Kenyan small businesses, made by LeziTech
(owner: MdauCodes). It is the first of several LeziTech products under lezitech.site.
- App: `suite.lezitech.site` (planned)
- Marketing pages: `lezitech.site/suite` (planned)
- Plans: **Lite KES 2,000**, **Growth KES 4,500**, **Pro KES 9,000** a month, with a 7-day free trial.
  - Lite: 2 logins, 1 branch, 500 items
  - Growth: 8 logins, 3 branches, 5,000 items
  - Pro: 30 logins, 10 branches, unlimited items

## 2. Where things are

| What | Where |
|---|---|
| API | `J:\backend\lezitech-suite-api`: Spring Boot 3.3, Java 21, JDBC + Flyway, Postgres with row-level security; port **8090** |
| Web app | `J:\frontend\lezitech-suite-web`: Vite, React 18, TypeScript, Tailwind 4, TanStack Query; port **5320** |
| Branch | `launch` in both repos. **Nothing is pushed**: `MdauCodes/lezitech-suite-api` and `lezitech-suite-web` don't exist on GitHub yet, so the user must create them. |
| Preview configs | `J:\backend\.claude\launch.json`: `suite-api`, `suite-web`, and `lezi-site-mockup` (Lovable design, port 5330) |
| Lovable website design | `J:\A lovable apps\erp-crm-pos` (not a git repo). Finished and styled by us: Home, Sectors, 9 sector pages, Pricing, Get set up, Style guide. Styles in `src/lezi.css`, page content in `src/lib/lezi-data.ts` and `lezi-details.ts`. |
| Second Lovable attempt | `J:\A lovable apps\pixel-perfect-replica-99-main`: weaker and unstyled; ignore it. |
| Lovable prompt we wrote | `J:\backend\lovable-prompt-lezi-suite.md` |
| Memory | `C:\Users\lenovo\.claude\projects\J--backend\memory\` (`lezi-suite-health-data.md`) and `C:\Users\lenovo\.claude\projects\C--Work-backend\memory\lezitech-suite-direction.md` (the original decisions) |

**Running locally**
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`. The API uses an embedded Postgres in `.localdb`.
- Tests: in the API folder, `JAVA_HOME=... ./mvnw.cmd -q test`, then read `target/surefire-reports/TEST-*.xml`. **72 tests, all passing** at commit `4016a32`.
- Web typecheck: `npx tsc -b --noEmit` in the web folder.
- The API must be **restarted** to pick up Java, JSON or migration changes (use preview_stop, then preview_start `suite-api`). If port 8090 says "reserved", it's only TIME_WAIT: wait about 8 seconds and retry.
- Demo workspaces (`/demo`) are seeded **once**. Changes to sample data only show in new signups.
- Control panel: `/admin/login`; one-click demo roles locally.

## 3. Rules from the user (always follow)

- **Git:** commit as MdauCodes using the existing global git config. **Never** add `Co-Authored-By: Claude` or "Generated with Claude Code". Commit or push only when the work is done, or when asked.
- **UI:** nothing that looks AI-made: no sparkle icons, no pill "eyebrow" labels, no floating badges, no bobbing animations. Plain, warm English for Kenyan small-business owners.
- **Passwords:** reset and first password use **6-digit email codes**, never links.
- **Don't claim features that don't exist** in sub-sector taglines, SEO text or marketing copy (see section 7).
- **Pharmacy and medical clinics stay "coming soon"** (health data) until the user says otherwise (section 4).

## 4. Product decisions

- **Sectors:** 9 packs in `src/main/resources/sectors.json`:
  - restaurant, hotel, retail, beauty, wholesale, pharmacy, services, tours, property
- **Sub-sectors:** 64 in `subsectors.json`, **59 open and 5 coming soon**. Each personalises its pack:
  - wording (`terms`), `features`, `kpis`, `modules`, `home`, `posMode`
  - sample items (`seed`), setup `questions`, `seo` text and `examples` (placeholder text)
  - Merged by `SectorCatalog.get(sector, subSector)`. Matching is by key or name, and the sub-sector name is what's stored in `tenants.sub_sector`.
- **Coming soon** (`"comingSoon": true`): the whole **pharmacy** pack and **Medical & dental clinic**. Signup and admin workspace creation are refused, there are no demos, and the picker shows "coming soon" with a link to the setup form.
  - Why: avoid holding health data and stay under the Data Protection Act registration exemption (turnover under KES 5M a year, under 10 staff; the user is the only staff member).
  - Remind the user to revisit registration near KES 420k a month of revenue.
  - Hospitality, education, property and transport may still trigger registration: the user should confirm with the ODPC or a lawyer.
- **Payments:**
  - Each business uses its own Daraja (till or paybill) or PayHero account, encrypted with `SECRETS_KEY` (`integrations` table). The team usually enters it in /admin; owners can in Settings.
  - LeziTech collects subscriptions through its own PayHero STK push.
  - The PayHero field names come from its PHP SDK and are **unverified**; one real sandbox payment is needed.
- **WhatsApp:** wa.me links by default. Optional WhatsApp Cloud API (`WHATSAPP` integration) sends receipts as an approved template named `lezi_receipt`.
- **SMS:** Africa's Talking credentials are stored and tested only. **Nothing is sent yet.**
- **Email:** `Mailer` sends through Brevo or Resend (`EMAIL_PROVIDER`). **Brevo isn't set up yet**, and password codes depend on it.
- **Tax** (`TaxController`, `/tax`):
  - VAT, Turnover Tax and the Tourism Fund levy, per day, month or year, with filing dates (VAT and TOT on the 20th, levy on the 9th).
  - TOT defaults to **1.5%** (KRA rate since July 2023). The user's old notes said 3%: confirm with them. All rates are editable.
  - eTIMS code exists but is switched off (`ETIMS_MODE=off`).
- **Assisted onboarding:**
  - Public "set it up for me" form → the team creates the workspace in /admin → hand-over starts the trial and sends a 6-digit welcome code.
- **Growth:**
  - **Ad source:** first-touch UTM, fbclid and gclid are kept in localStorage (`src/lib/tracking.ts`) and sent with signup and setup requests (`tenants.acquisition`, `setup_requests.acquisition`).
  - **Meta Conversions API** (`growth/MetaConversions`): a queue (`marketing_events`) sending Lead, StartTrial, Subscribe and Purchase. Emails and phones are hashed. Off until `META_PIXEL_ID` and `META_CAPI_TOKEN` are set; the browser Pixel needs `VITE_META_PIXEL_ID`.
  - **Referrals:** each workspace has a code; both businesses get a month free on the referred business's first payment.
  - **Ad results:** /admin/ads shows source and campaign → requests, trials, paying and collected, with spend typed in to get cost per paying customer.
- **Commissions:**
  - `users.commission_rate`, with `items.commission_rate` overriding it.
  - `commission_payouts` table; `GET /api/reports/commissions`, `POST /api/reports/commissions/payouts`.
  - A Commissions card on Reports (shown when `features.commissions` is on).
- **Ads budget and marketing:** KES 20k to start, mostly Meta click-to-WhatsApp with some Google Search. ChatGPT ads aren't available in Kenya.
  - Scenarios (in the scratchpad `scenarios.py`): best case reaches 2,000 clients around month 19; moderate around month 36.

## 5. Architecture notes and gotchas

- `tenants` and `users` have **no RLS**: always filter by id or `tenant_id`. Business tables use the `tenant_fence` policy on `app.tenant`.
- `Db` helper: rows come back with **camelCase** keys, and `:t` and `:u` are filled in from the caller.
- Don't put `@Transactional` on admin endpoints that create workspaces (RLS context; see OnboardingController).
- JDBC uses `?` as a placeholder, so **don't use Postgres's `?` jsonb operator** in JdbcTemplate SQL. Use `->> 'key' IS NOT NULL`.
- **Shell pitfalls:**
  - Bash heredocs containing apostrophes or `\t` get mangled. Write a Python script with the Write tool and run it, or use the Edit tool.
  - In PowerShell, `R` is a built-in alias (Invoke-History), so don't name helper functions `R`.
- Every API test in `src/test/java` extends `IntegrationTest`. Helpers: `signup(sector)`, `token()`, `staff(owner, role)`, `userId()`, `item(token, name)`, and `post`, `get`, `call`.
- `SubSectorTest.everySubSectorSignsUpWithItsSampleData` signs up every open sub-sector, so any typo in `subsectors.json` fails here.

## 6. Built and committed (latest first)

API on `launch`:
- `4016a32` commissions, honest sub-sector wording, garage and 4 more businesses, example text
- `8388050` pharmacy and clinics coming soon
- `1e23c77` 59 personalised sub-sectors
- `830ee40` ad attribution, Meta Conversions API, referrals
- `39063ba` WhatsApp Cloud API receipts
- `1de82a2` tax summary
- `886faf4` emailed receipts
- `d3c59c6` assisted onboarding and 6-digit codes
- `0b793a0` per-business M-Pesa
- `dd3d2aa` rename and eTIMS off
- `bb0f90f` plan limits
- Earlier: trial reminders, offline till, eTIMS client, M-Pesa, backups, role checks, refunds, idempotency

Web on `launch`, matching:
- `00a73ba` commissions screens and example text
- `b7ac321` coming soon
- `0a3e154` signup sub-sector questions
- `75ce15f` tracking, referral card, Ad results
- `3189ab6` WhatsApp setup card
- `b372652` Tax page
- `488397a` receipt sending
- `4f12f05` setup form, reset by code, Excel import
- `2591a0c` payments setup
- `8074c5e` rebrand to Lezi Suite
- `b428b61` plan limits

## 7. Not done: blockers for go-live

1. **GitHub repos and push.** The user must create the two repos. Then `git push -u origin launch` in both.
2. **Brevo email:** API key plus the domain's email DNS records (SPF and DKIM). Without it, password codes don't arrive.
3. **PayHero:** one real sandbox payment to confirm the field names.
4. **Deploy:** Contabo + Coolify; a real Postgres with backups; `suite.lezitech.site` with HTTPS. Secrets to set:
   - `SECRETS_KEY`, `JWT_SECRET`, `ADMIN_EMAIL` and `ADMIN_PASSWORD`, `TEAM_EMAIL`
   - `PAYHERO_*`, `MPESA_*`, `META_*`, `SITE_URL`, `APP_URL`, `CORS_ORIGINS`

   Callbacks need public HTTPS. **Suggested: set up a test server early**, so external accounts can be verified while building.
5. **Ad source lost between domains (bug):** the website on `lezitech.site` and the app on `suite.lezitech.site` don't share localStorage. The website's Start and Setup links must carry the UTM, fbclid, gclid and ref parameters to the app (`captureAcquisition()` already reads them from the URL). Fix this with the SEO pages.
6. **Privacy policy and terms**, including data processing terms for the data businesses keep about their customers. The website only has placeholders.
7. **Open questions for the user:**
   - Hide "Beauty clinic" too? It may hold health-like notes.
   - Turnover Tax: 1.5% or 3%?

## 8. Next work, in order

1. **Design elevation.** The user found the app and website "too whitish and generic" and wants them **clean, elegant, unique but simple to understand**.
   - **App: done 2026-11-10** (web commit `7fd83d0`):
     - tokens in `src/index.css` (`bg-bg`, `bg-side`, `text-soft`, `bg-lime`, `.label-mono`), Manrope and JetBrains Mono in `index.html`
     - ink sidebar grouped Today, Sell, Stock & people, Money, Business (`AppShell.tsx`)
     - `Stat feature` gives the ink tile with a lime number; `PageHeader caption` gives the mono date line
     - Home redesigned; Input, Select and Textarea respect a width class
   - **Done 2026-11-10** (web commit after `7fd83d0`): `AuthLayout` (ink panel + form) for login, reset and the
     control-panel sign-in; branded `/w/<slug>` sign-in kept in the business's colours; control panel sidebar grouped
     Today, Businesses, Money, Product, LeziTech in the app's ink style, MRR on the ink tile; signup with an ink side
     panel and ink/lime steps; till cart with an ink order-type switch and the total on an ink tile; modals with a
     softer overlay, bolder title and quiet footer.
   - Still plain: control-panel inner pages (tables), the public customer pages (menu, order board, tracking), Demo and
     Set-it-up pages.
   - **Website: still the old plain template at `/`.** Replace it with the Lovable design as part of the SEO step.
   - Direction: the Lovable look, used consistently:
     - off-white canvas `#f6f7f5`, ink `#101415`, lime `#b8f24a` as a fill only
     - Manrope, with JetBrains Mono for labels
     - an ink sidebar, white cards with fine borders, and clear stat tiles
   - Each workspace's brand colour stays for its own highlights (`applyBrand` in `theme.ts`).
2. **SEO, GEO and AEO:**
   - Pages generated ahead of time for each sector, sub-sector and town, from `SectorCatalog.pages()` and the `seo` blocks, on the Lovable design.
   - JSON-LD (Organization, SoftwareApplication with KES offers, FAQPage, BreadcrumbList), a sitemap, and `noindex` on all app screens.
   - Pharmacy and clinics shown as "coming soon"; plus the cross-domain fix from item 5 of section 7.
3. **GA4 and Google Ads conversion tracking**, alongside the Meta tracking that already exists.
4. **Guides and comparison pages:** Claude drafts, the user reviews.
5. **Deploy** and run through the go-live checklist.

## 9. Feature gaps (after go-live; tell the user before promising any)

- **Most valuable to owners:**
  - nightly summary to the owner (WhatsApp or email)
  - expenses and profit and loss
  - what I owe suppliers
  - customer statements and debt reminders
  - SMS sending
  - buy by the carton, sell by the piece
  - stock per size and colour
- **Sector-specific:**
  - petrol pump meter readings
  - gym membership renewals
  - school classes, terms and fee statements
  - laundry "ready" status
  - apartment water billing
  - distributor sales reps and routes
  - phone serial (IMEI) numbers and warranties
  - Airbnb and Booking.com sync
  - daily market-stall fees
- **Platform:**
  - "Get started" checklist, quiet-trial list, support button
  - payroll (PAYE, NSSF, SHIF, Housing Levy)
  - eTIMS (start the KRA integrator application early; it takes weeks)
  - Bluetooth receipt printers
  - report export beyond the sales CSV
  - data export for customers
  - security review
  - web tests
- **Services pack:** it has no stock screen. Sub-sectors that need stock (garage, furniture, printing) override `modules`.
