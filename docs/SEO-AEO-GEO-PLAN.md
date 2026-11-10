# Lezi Suite: SEO, AEO and GEO plan

Written 2026-11-10. Owner: MdauCodes. Status: **plan, waiting for decisions in section 9**.

Goal: Kenyan business owners find Lezi Suite when they search Google or Bing, when Google answers with an AI overview, and
when they ask ChatGPT, Gemini or Perplexity "what POS should I use for my hardware shop in Nakuru?", and then sign up or ask
for setup. Success is measured in **trials and paying businesses by landing page**, not traffic.

- **SEO**: rank in normal search results.
- **AEO** (answer engine optimisation): be the quoted answer in featured snippets, "People also ask" and Google's AI overviews.
- **GEO** (generative engine optimisation): be named and linked when ChatGPT, Gemini, Perplexity or Copilot answer.

All three rest on the same base: fast pages whose content is in the HTML (not built by JavaScript), clear facts, direct
answers, structured data, and Lezi Suite mentioned on other trusted sites.

---

## 1. Where the website lives and how it's built

**Recommendation: the Lovable design project becomes the marketing site**, built ahead of time into static HTML.
- `J:\A lovable apps\erp-crm-pos` already has the design: Home, Sectors, sector pages, Pricing, Get set up, Style guide.
- It runs on TanStack Start, which can **prerender every page to static HTML**. That's ideal for search engines and AI crawlers, and fast on Kenyan mobile data.
- It deploys as its own small container on Coolify, served at **`lezitech.site/suite`**, as decided. The app stays on `suite.lezitech.site`.
- Page content isn't typed twice: at build time the site reads the sector and sub-sector data from the API repo (`sectors.json`, `subsectors.json`, `seo` blocks, `examples`, sample items).

**Alternative considered:** putting the pages inside the app (Vite single-page app) with a prerender step.
- Rejected: the app is a client-side app with logins, mixing marketing into it slows both, and the app must never be indexed.

**Domain routing:** `lezitech.site/suite/*` must reach the marketing container.
- If lezitech.site is hosted elsewhere, its server or proxy forwards `/suite` to the container. Coolify's Traefik can do this when lezitech.site also runs on the Contabo server.
- The site is built with base path `/suite`.

The old plain home page in the app (`/` and `/for/:sector`) becomes a redirect to `lezitech.site/suite` once the site is live.

## 2. Pages (information architecture)

All under `https://lezitech.site/suite`:

| Page | Count | Purpose |
|---|---|---|
| `/` Home | 1 | Brand searches, overview, links to everything |
| `/sectors` | 1 | Hub for the 9 sectors |
| `/sectors/{sector}` | 9 (pharmacy marked coming soon) | "restaurant POS Kenya", "hotel management system Kenya" |
| `/sectors/{sector}/{sub}` | 59 open (+5 coming soon) | Long-tail: "hardware shop POS Kenya", "car wash software Kenya" |
| `/towns/{town}` | 10 at launch | "POS system Nairobi", "POS Mombasa". Real local content only (below). |
| `/towns/{town}/{sector}` | about 15 chosen pairs | Only where people actually search the pair, e.g. "restaurant POS Nairobi" |
| `/pricing` | 1 | "POS price Kenya", plan questions |
| `/get-set-up` | 1 | Lead form, "set it up for me" |
| `/guides/{slug}` | 5 at launch, then 1 to 2 a week | How-to and explainer content (AEO) |
| `/compare/{slug}` | 3 at launch | "notebook vs POS", "Excel vs POS", "M-Pesa till vs STK prompt" |
| `/tools/{slug}` | 2 at launch | Free calculators: Turnover Tax, VAT (strong links and AI citations) |
| `/about`, `/contact`, `/privacy`, `/terms` | 4 | Trust signals; privacy and terms are needed anyway (Data Protection Act) |

**Coming-soon sectors** (pharmacy, medical clinic) get a page that says so, with a "tell me when it's ready" form. These pages are indexed: they collect leads and don't promise features.

### Avoiding thin and duplicate pages (the main risk)
59 sub-sector pages built from one template could look like doorway pages to Google. Each page must be genuinely different:
- **Built from data that differs per sub-sector:**
  - headline and pitch (`seo`)
  - its own sample items and prices
  - wording ("job cards", "washes", "tabs")
  - home-screen KPIs
  - setup questions
  - a dashboard preview built from its own sample items
- **Plus 150 to 300 words of hand-reviewed copy** per page: a day in that business, its common problems, and 3 FAQs.
- **Order of writing:** the top 20 sub-sectors get full copy for launch:

  | Group | Sub-sectors |
  |---|---|
  | Shops | minimart, hardware, agrovet, boutique, phones, liquor, butchery, auto spares, gas & water |
  | Restaurants | restaurant, café, bar, fast food |
  | Appointments and services | salon, barber, car wash, garage |
  | Others | hotel, guest house, mall |

- **The other 39 go live with `noindex`** until their copy is written and reviewed, and are switched on in batches.
- **Town pages are only published with real local content:**
  - town-specific examples
  - local market names (Gikomba, Kongowea, Kibuye)
  - the KRA service centre
  - delivery or support coverage
  - later, customer quotes from that town

  No find-and-replace town pages.

## 3. Technical SEO checklist

**Every page:**
- [ ] Prerendered HTML with the full text: no content that only appears after JavaScript.
- [ ] Unique `<title>` (under 60 characters) and meta description (under 155), one `<h1>`, logical h2 and h3.
- [ ] `lang="en-KE"`.
- [ ] Canonical URLs, all `https://lezitech.site/suite/...`, with no trailing-slash duplicates.
- [ ] Open Graph and Twitter cards with an image per sector, generated at build from the dashboard previews.

**Site-wide:**
- [ ] `sitemap.xml` for indexable pages only, with `lastmod` set at build.
- [ ] `robots.txt` that points to the sitemap.
- [ ] **The app (`suite.lezitech.site`) must never be indexed:**
  - `robots.txt: Disallow: /` and an `X-Robots-Tag: noindex` header on every response
  - except `/demo`, which may be indexed (as decided)
  - the API sends `X-Robots-Tag: noindex` on everything
- [ ] Speed (Core Web Vitals on a mid-range Android over 4G):
  - LCP under 2.5s
  - WebP images, lazy below the fold
  - preloaded Manrope subset; no heavy scripts
  - the Meta Pixel and GA4 load after first paint
- [ ] Internal links: home → sectors → sub-sectors, and towns ↔ sectors, with breadcrumbs everywhere. Each sub-sector page links to 3 related sub-sectors and its sector.
- [ ] 404 page with links to sectors and pricing; 301 redirects from the app's old `/for/:sector` pages.
- [ ] **Search consoles:** Google Search Console and **Bing Webmaster Tools**. Bing matters doubly because ChatGPT's search uses Bing's index. Submit the sitemap to both.

### Structured data (JSON-LD)
| Schema | Where |
|---|---|
| `Organization` (LeziTech: name, logo, URL, contact WhatsApp and phone, `sameAs` social profiles) | every page |
| `SoftwareApplication` (Lezi Suite, BusinessApplication, operatingSystem Web/Android, `offers` for Lite, Growth and Pro in KES) | home, pricing, sector pages |
| `FAQPage` | every page with an FAQ block |
| `BreadcrumbList` | everything below the home page |
| `HowTo` | step-by-step guides |
| `Article` (author, dates) | guides and posts |
| `WebApplication` with a free offer | tool pages |

No `AggregateRating` until there are real reviews: invented ratings get penalised.

## 4. AEO: being the quoted answer

- **Every page opens its main sections with a one or two sentence direct answer**, then the detail. For example: "Turnover Tax in Kenya is 1.5% of gross sales for businesses earning under KES 25 million a year, filed and paid on iTax by the 20th of the next month."
- **Question-shaped headings** that match what people type: "How much does a POS system cost in Kenya?", "Can I use M-Pesa with a POS?", "Does a POS work without internet?"
- **Tables for comparisons and prices**, which snippets love: plans, a tax rates table, "notebook vs Excel vs POS".
- **FAQ blocks:** 3 to 6 real questions per page, answered in plain sentences, marked up with `FAQPage`.
- **Numbers and facts with sources and dates:** KRA rates, the Tourism Fund levy, M-Pesa till vs paybill. Show "Updated November 2026" and keep it current.
- **Seed questions to answer first** (the user can add more from WhatsApp enquiries):
  - How much is a POS system in Kenya?
  - Best POS for a small shop in Kenya
  - How do I connect M-Pesa till to my POS?
  - How to calculate Turnover Tax in Kenya
  - Do I need eTIMS?
  - How to track stock in a minimart
  - How to stop staff stealing at the till
  - How to pay salon commissions
  - How to run a car wash business in Kenya
  - Hardware shop stock list and pricing tips

## 5. GEO: being named by ChatGPT, Gemini, Perplexity and Copilot

AI assistants answer from what they can crawl and from what other sites say. To be named:
1. **Let AI crawlers in.** Allow in `robots.txt`: `GPTBot`, `OAI-SearchBot`, `ChatGPT-User`, `PerplexityBot`, `ClaudeBot`, `Google-Extended`, `Bingbot`.
   - This is a decision for the owner (section 9). Allowing it is recommended, since being cited is the goal.
2. **`/llms.txt`:** a short, plain summary of Lezi Suite with links to the key pages (sectors, pricing, guides, FAQ). Cheap and increasingly read.
3. **Clear, consistent facts about the product:** what it is, who it's for, the prices in KES, M-Pesa, offline, and "made in Kenya by LeziTech". The same wording on our site, the Google Business Profile, social bios and directory listings, so assistants see one consistent description.
4. **Mentions on other sites** (the biggest lever for GEO):
   - Google Business Profile for LeziTech
   - Kenyan business and software directories, and startup lists
   - a LinkedIn company page
   - answers on Reddit (r/Kenya, r/KenyaBusiness) and Quora, where genuinely helpful
   - guest posts on Kenyan business blogs
   - partners (accountants, POS hardware sellers) linking to us
   - local tech press when there's news (a launch, a customer story)
5. **Citable assets:** the free tax calculators, a "Kenya small business tax dates 2026/27" page, and later data like "average M-Pesa share of sales by sector", from anonymised totals with consent. Assistants and journalists link to useful tools and numbers.
6. **Track AI referrals:**
   - The ad-source capture already stores the referrer host. Add `chatgpt.com`, `perplexity.ai`, `gemini.google.com` and `copilot.microsoft.com` as named sources on the Ad results page.
   - Check monthly by asking each assistant our target questions and noting whether Lezi Suite appears.

## 6. Measurement

- **Google Search Console and Bing Webmaster:** impressions, clicks, queries, indexed pages, Core Web Vitals.
- **GA4:** page views and conversions (`setup_request`, `start_trial`). It loads only on the marketing site, and with a consent banner if we add one; the Data Protection Act favours consent for analytics cookies.
- **Lezi Suite's own records:**
  - every signup and setup request already stores its landing page and source
  - add "landing page" to the Ad results page to see trials and paying businesses per SEO page
- **Monthly review:** top landing pages by paying businesses, pages with impressions but low clicks (rewrite titles), questions people ask (new FAQs or guides).

## 7. Must fix alongside: the ad source lost between domains

The website (`lezitech.site`) and the app (`suite.lezitech.site`) don't share browser storage.
- **Fix:** the website keeps the first visit's UTM, fbclid, gclid and ref, and **appends them to every "Start free" and "Set it up for me" link** into the app. The app's `captureAcquisition()` already reads them from the URL.
- The website also fires the Meta Pixel PageView and GA4 itself.

## 8. Content plan

**Launch set** (Claude drafts, owner reviews):

| Item | Count |
|---|---|
| Home, Sectors hub, 9 sector pages | 11 |
| Sub-sector pages with full copy | 20 (the others `noindex` until written) |
| Town pages | Nairobi, Mombasa, Kisumu, Nakuru, Eldoret, Thika, Machakos, Nyeri, Meru, Kakamega |
| Town and sector pairs | Nairobi × restaurant, shop, salon, hardware, hotel; Mombasa × restaurant, hotel, shop; Kisumu × restaurant, shop; Nakuru × shop, hardware, agrovet; Eldoret × agrovet, shop |
| Guides | How to connect your M-Pesa till to a POS; Turnover Tax explained (with the calculator); How to stop stock and cash going missing; How to set up a minimart POS in an afternoon; How to pay salon and car wash commissions fairly |
| Comparisons | Notebook vs Excel vs POS; M-Pesa till payment vs STK prompt; Lezi Suite plans compared. Named competitor comparisons only after the owner confirms facts about them. |
| Tools | Turnover Tax calculator; VAT calculator (both reuse our tax logic) |
| Trust pages | About, Contact, Privacy, Terms |

**After launch:** 1 to 2 guides a week, picked from Search Console queries and WhatsApp questions, plus switching on the remaining sub-sector pages in batches of 10 as their copy is reviewed.

**Later, phase 3:**
- Swahili versions of the top pages, since many owners search in Swahili or Sheng, with `hreflang`.
- Customer stories with photos and permission.

## 9. Decisions needed from the owner

1. **Website build:** the Lovable project, prerendered, at `lezitech.site/suite` (recommended), or inside the app?
2. **lezitech.site today:** where is it hosted, and can `/suite` be forwarded to the new container? Or should the marketing site use `suite.lezitech.site` as its root, with the app moving to `app.lezitech.site`?
3. **AI crawlers:** allow GPTBot, PerplexityBot, ClaudeBot and Google-Extended? Recommended: yes.
4. **Towns:** confirm or edit the launch list of 10, and say which towns you can actually serve (setup visits, support).
5. **Review time:** about 20 sub-sector pages plus 5 guides plus 10 town pages to review before 22 Nov. Is that realistic, or should launch be smaller (the top 10 sub-sectors, 5 towns, 3 guides)?
6. **Accounts:** Google Search Console, Bing Webmaster Tools and GA4 (Google account), and a Google Business Profile for LeziTech. The owner creates these; Claude sets up the code.
7. **Named competitor comparisons:** which competitors, if any?

## 10. Build order and timing (assuming go-live 22 Nov)

| Days | Work |
|---|---|
| 11 to 12 Nov | Site wired to the sector data; sector and sub-sector templates; prerender; sitemap, robots, llms.txt; JSON-LD; ad source carried into the app links; noindex on the app |
| 13 to 14 Nov | Home, pricing, set-up and trust pages; town and pair templates; the two tax calculators; OG images |
| 15 to 17 Nov | Content: the top 20 sub-sectors, 10 towns, 5 guides, 3 comparisons. Claude drafts, owner reviews in batches. |
| 18 Nov | Speed pass, link check, structured-data validation (Rich Results Test), mobile check |
| 19 to 21 Nov | Deploy with the app; Search Console and Bing verification; submit sitemaps; Google Business Profile |
| 22 Nov | Live. Start the weekly content rhythm and the monthly AI-assistant check. |

**Realistic expectations:** new sites take 2 to 4 months to rank on Google, and AI assistants pick up new sources over weeks to months. Search won't bring many customers in the first month. That's what the ads are for. By month 3 to 6 it should become the cheapest source of trials.
