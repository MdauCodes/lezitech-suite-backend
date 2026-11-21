# BizNez: Google Ads Strategy

**Written:** 2026-11-12
**Status:** Planning document — for implementation after go-live
**Budget:** KSh 20,000/month (Months 1–3), scaling to KSh 80,000/month by Month 10

---

## 1. Which Google Ads Campaign Types to Use

Google offers 8 active campaign types in 2026. For BizNez, **three of them matter**. The rest are either irrelevant or too expensive for your stage.

| Campaign Type | Use for BizNez? | Why |
|--------------|----------------|-----|
| **Search** | ✅ **Primary** | High-intent buyers actively searching for "POS Kenya", "restaurant software Nairobi". Best conversion rate (3–5% for SaaS). |
| **Performance Max** | ✅ **Phase 2** | Scales volume across all Google inventory. Needs 50+ conversions/month to work well. Start in Month 4+. |
| **Demand Gen** | ⚠️ **Phase 3** | Upper-funnel awareness (YouTube, Discover, Gmail). Cheap CPC but very low conversion (0.14–0.30%). Only after brand is known. |
| Display | ❌ Skip | 0.30% conversion, KSh 219 cost per lead for B2B SaaS. Not worth the budget. |
| Video | ❌ Skip | Awareness-only. No purchase intent. |
| Shopping | ❌ Skip | For e-commerce products, not SaaS. |
| App | ❌ Skip | No mobile app yet. |
| AI Max for Search | ⚠️ **Future** | Google's newest AI-driven search. Requires mature conversion data. Consider after 6 months. |

### Recommendation: Start with Search Only

**Months 1–3:** 100% Search campaigns.
**Months 4–6:** 70% Search, 30% Performance Max.
**Months 7–12:** 60% Search, 30% Performance Max, 10% Demand Gen.

**Why Search first:**

1. **Highest intent.** A person searching "restaurant POS Kenya" is actively looking to buy. A person scrolling YouTube is not.
2. **Best conversion rate.** B2B SaaS Search converts at 3–5% click-to-trial. Performance Max converts at 0.64–6.53% but blends in low-intent display traffic that drags the average down.
3. **Cheapest to learn.** With KSh 20,000/month (~800 clicks), you'll get 24–40 trial sign-ups from Search alone. That's enough data to optimize. PMax needs 50+ conversions/month to exit learning mode — you won't hit that until Month 4+.
4. **Full control.** You choose the keywords, write the ad copy, and control which landing page each ad group sends traffic to. PMax automates everything, which is great at scale but opaque at small budgets.

---

## 2. Search Campaign Structure

### 2.1 Account Structure Overview

```
BizNez Google Ads Account
│
├── Campaign 1: "Sector-Specific POS" (70% of budget)
│   ├── Ad Group: Restaurant POS
│   ├── Ad Group: Retail POS
│   ├── Ad Group: Salon/Barber POS
│   ├── Ad Group: Hotel POS
│   ├── Ad Group: Hardware/Wholesale POS
│   ├── Ad Group: Service Business POS
│   └── Ad Group: Tour/Property POS
│
├── Campaign 2: "Generic Business Software" (20% of budget)
│   ├── Ad Group: POS Software Kenya
│   ├── Ad Group: Business Management Software
│   └── Ad Group: M-Pesa Integration Software
│
└── Campaign 3: "Branded & Competitor" (10% of budget)
    ├── Ad Group: BizNez Brand
    └── Ad Group: Competitor Alternatives
```

### 2.2 Campaign 1: Sector-Specific POS (KSh 14,000/month)

**This is your money-maker.** Each ad group targets a specific sector and sends traffic to that sector's landing page.

#### Ad Group: Restaurant POS

| Component | Details |
|-----------|---------|
| **Keywords** | "restaurant POS Kenya", "restaurant software Nairobi", "table ordering system Kenya", "kitchen display system Kenya", "restaurant billing software M-Pesa", "food ordering system Kenya", "restaurant management system price" |
| **Match types** | Phrase match ("restaurant POS Kenya") + Exact match ([restaurant POS Kenya]) + Broad match modified (restaurant + POS + Kenya) |
| **Landing page** | `biznez.com/sectors/restaurant` |
| **Ad headline 1** | Restaurant POS Kenya — From KSh 1,000 First Month |
| **Ad headline 2** | Table Ordering, Kitchen Display, M-Pesa |
| **Ad headline 3** | BizNez — 7-Day Free Trial, No Card Needed |
| **Ad description 1** | QR table ordering. Kitchen display. M-Pesa STK. Works offline. Try free for 7 days. |
| **Ad description 2** | Built for Kenyan restaurants. Tips, loyalty, ETR. First month KSh 1,000. |
| **Sitelinks** | "View Pricing", "See Demo", "Restaurant Features", "Get Set Up" |
| **Callout extensions** | "M-Pesa Integration", "Works Offline", "No Installation", "7-Day Free Trial" |

#### Ad Group: Retail POS

| Component | Details |
|-----------|---------|
| **Keywords** | "retail POS Kenya", "shop billing software", "minimart POS system", "boutique management software", "retail inventory system Kenya", "shop POS with M-Pesa" |
| **Landing page** | `biznez.com/sectors/retail` |
| **Ad headline 1** | Retail POS Kenya — Stock, Sales, M-Pesa in One System |
| **Ad headline 2** | From KSh 1,000 First Month · 7-Day Free Trial |
| **Ad description 1** | Track stock, sales, and customer credit. Works on phone, tablet, desktop. Offline mode. |

#### Ad Group: Salon/Barber POS

| Component | Details |
|-----------|---------|
| **Keywords** | "salon management software Kenya", "barber POS system", "salon booking system Nairobi", "hair salon billing software", "salon commission tracking Kenya" |
| **Landing page** | `biznez.com/sectors/salon` |
| **Ad headline 1** | Salon POS Kenya — Commissions, Bookings, M-Pesa |
| **Ad headline 2** | First Month KSh 1,000 · Free 7-Day Trial |
| **Ad description 1** | Track commissions, manage appointments, accept M-Pesa. Built for Kenyan salons. |

#### Ad Group: Hotel POS

| Component | Details |
|-----------|---------|
| **Keywords** | "hotel management system Kenya", "guest house software Nairobi", "hotel billing system M-Pesa", "hotel POS Kenya", "guest house management software" |
| **Landing page** | `biznez.com/sectors/hotel` |

#### Ad Group: Hardware/Wholesale POS

| Component | Details |
|-----------|---------|
| **Keywords** | "hardware shop POS Kenya", "wholesale billing software", "hardware inventory system", "wholesale management software Kenya", "customer statement software Kenya" |
| **Landing page** | `biznez.com/sectors/wholesale` |
| **Ad headline 1** | Hardware Shop POS — Customer Statements, Pack & Loose |
| **Ad description 1** | Track customer credit, manage stock by batch. M-Pesa integration. Free trial. |

#### Ad Group: Service Business POS

| Component | Details |
|-----------|---------|
| **Keywords** | "service business software Kenya", "car wash management system", "garage billing software", "service booking system Kenya" |
| **Landing page** | `biznez.com/sectors/services` |

#### Ad Group: Tour/Property POS

| Component | Details |
|-----------|---------|
| **Keywords** | "tour company software Kenya", "property management system Nairobi", "tour booking software", "rental management Kenya" |
| **Landing page** | `biznez.com/sectors/tours` |

### 2.3 Campaign 2: Generic Business Software (KSh 4,000/month)

**Catches high-intent searches that don't mention a specific sector.**

#### Ad Group: POS Software Kenya

| Component | Details |
|-----------|---------|
| **Keywords** | "POS software Kenya", "best POS system Kenya", "POS system price Kenya", "affordable POS Kenya", "POS with M-Pesa Kenya", "cloud POS Kenya" |
| **Landing page** | `biznez.com` (home page) |
| **Ad headline 1** | POS Software Kenya — From KSh 1,000 First Month |
| **Ad headline 2** | M-Pesa, Offline, 59 Business Types |
| **Ad description 1** | One system for sales, stock, and customers. Works on any device. 7-day free trial. |

#### Ad Group: Business Management Software

| Component | Details |
|-----------|---------|
| **Keywords** | "business management software Kenya", "SME software Kenya", "small business software Nairobi", "ERP for small business Kenya" |
| **Landing page** | `biznez.com` (home page) |

#### Ad Group: M-Pesa Integration Software

| Component | Details |
|-----------|---------|
| **Keywords** | "M-Pesa integration software", "STK prompt software Kenya", "business M-Pesa payment system", "Daraja API software" |
| **Landing page** | `biznez.com/pricing` |

### 2.4 Campaign 3: Branded & Competitor (KSh 2,000/month)

**Protects your brand and captures competitor traffic.**

#### Ad Group: BizNez Brand

| Component | Details |
|-----------|---------|
| **Keywords** | "BizNez", "BizNez POS", "BizNez login", "biznez.com" |
| **Landing page** | `biznez.com` |
| **Bid strategy** | Low bid — brand searches are cheap and high-converting |

#### Ad Group: Competitor Alternatives

| Component | Details |
|-----------|---------|
| **Keywords** | "Odoo POS alternative Kenya", "ERPNext alternative Kenya", "Tillpoint alternative Kenya", "Square POS Kenya alternative" |
| **Landing page** | `biznez.com/compare` (comparison page) |
| **Ad headline 1** | Better Than Odoo for Kenyan SMEs — BizNez |
| **Ad description 1** | Built for Kenya. M-Pesa, offline, sector-specific. From KSh 1,000 first month. |

---

## 3. Keyword Strategy

### 3.1 Keyword Match Types

| Match Type | Example | When It Triggers | Use For |
|-----------|---------|------------------|---------|
| **Exact** | `[restaurant POS Kenya]` | Only "restaurant POS Kenya" (or close variants) | High-intent, proven converters |
| **Phrase** | `"restaurant POS Kenya"` | "best restaurant POS Kenya", "restaurant POS Kenya price" | Most of your campaigns |
| **Broad** | `restaurant POS Kenya` | "POS for restaurants in Nairobi", "Kenya restaurant software" | Top-of-funnel discovery |

**Recommendation:** Start with phrase match for all keywords. After 2 weeks, move your top-converting keywords to exact match and add negative keywords for irrelevant searches.

### 3.2 Negative Keywords

Add these immediately to prevent wasted spend:

| Category | Negative Keywords |
|----------|-----------------|
| **Free/cheap** | "free POS", "open source POS", "download POS", "POS source code" |
| **Jobs** | "POS technician", "POS repair", "POS installer job", "hiring" |
| **Hardware** | "POS printer", "POS machine", "barcode scanner", "cash register hardware" |
| **Not Kenya** | "USA", "UK", "Canada", "Australia", "India" |
| **Competitors** | "Odoo", "ERPNext", "Square", "Tillpoint" (unless in competitor campaign) |
| **Not SaaS** | "POS tutorial", "how to build POS", "POS course" |

### 3.3 Keyword Research Sources

Use these tools to expand your keyword list:

1. **Google Keyword Planner** (free with Google Ads account) — search volume and CPC estimates for Kenya.
2. **Google Trends** (free) — compare keyword popularity over time in Kenya.
3. **Google Search Console** (free) — once your site is live, see what people actually search to find you.
4. **AnswerThePublic** (free tier) — find long-tail questions people ask about POS in Kenya.

### 3.4 Long-Tail Keyword Strategy

Your 59 sub-sector landing pages are designed for long-tail keywords. These are your secret weapon:

| Long-Tail Keyword | Monthly Search Volume (Est.) | Competition | CPC (KSh) |
|------------------|-----------------------------|-------------|-----------|
| "hardware shop POS Kenya" | 50–100 | Low | 15–25 |
| "agrovet billing software" | 20–50 | Low | 10–20 |
| "car wash management system Nairobi" | 30–60 | Low | 15–30 |
| "butchery POS system Kenya" | 10–30 | Very low | 10–15 |
| "guest house software Mombasa" | 10–20 | Very low | 10–20 |

**Why long-tail wins:**
- Lower CPC (less competition)
- Higher conversion (more specific intent)
- Easier to rank organically (SEO synergy)
- Matches your sector landing pages perfectly

---

## 4. Ad Copy Best Practices

### 4.1 Responsive Search Ads (RSAs)

Google's RSA format lets you provide multiple headlines and descriptions, and Google tests combinations automatically.

**Structure for each RSA:**

| Field | Count | Example |
|-------|-------|---------|
| **Headlines** | 3–5 (max 15) | "Restaurant POS Kenya — From KSh 1,000" |
| **Descriptions** | 2–4 (max 4) | "QR table ordering. Kitchen display. M-Pesa STK. Works offline." |
| **Path** | 2 | `biznez.com/Restaurant-POS` |

### 4.2 Ad Copy Principles for BizNez

1. **Lead with the price.** "From KSh 1,000 First Month" is your strongest differentiator. Put it in headline 1.
2. **Mention M-Pesa.** Every ad should reference M-Pesa. It's the #1 trust signal for Kenyan businesses.
3. **Sector-specific language.** A restaurant ad says "table ordering" and "kitchen display". A retail ad says "stock tracking" and "customer credit". Don't use generic copy.
4. **Free trial CTA.** "7-Day Free Trial, No Card Needed" reduces friction.
5. **Social proof (when available).** "Trusted by 200+ Kenyan Businesses" — add this once you have the numbers.
6. **Intro offer urgency.** "First Month KSh 1,000 — Limited Offer" — creates urgency without being pushy.

### 4.3 Ad Copy Examples by Sector

#### Restaurant

> **Headline 1:** Restaurant POS Kenya — From KSh 1,000 First Month
> **Headline 2:** Table Ordering, Kitchen Display, M-Pesa
> **Headline 3:** BizNez — 7-Day Free Trial
> **Description:** QR codes per table. Kitchen sees orders instantly. Customers track & pay via M-Pesa. Works offline.

#### Retail

> **Headline 1:** Retail POS Kenya — Stock, Sales, M-Pesa
> **Headline 2:** From KSh 1,000 · 7-Day Free Trial
> **Description:** Track stock, sales, and customer credit. Works on phone, tablet, desktop. No installation needed.

#### Salon

> **Headline 1:** Salon POS — Commissions, Bookings, M-Pesa
> **Headline 2:** First Month KSh 1,000 · Free Trial
> **Description:** Track staff commissions, manage appointments, accept M-Pesa payments. Built for Kenyan salons.

#### Generic

> **Headline 1:** POS Software Kenya — From KSh 1,000
> **Headline 2:** M-Pesa, Offline, 59 Business Types
> **Headline 3:** BizNez by LeziTech — Legitimacy in Business Tech
> **Description:** One system for sales, stock, and customers. Works on any device. 7-day free trial, no card needed.

---

## 5. Budget Allocation

### 5.1 Month 1–3: KSh 20,000/month

| Campaign | Daily Budget | Monthly Budget | Expected Clicks | Expected Trials |
|----------|-------------|----------------|----------------|----------------|
| Sector-Specific POS | KSh 467 | KSh 14,000 | 560 | 14–28 |
| Generic Business Software | KSh 133 | KSh 4,000 | 160 | 4–8 |
| Branded & Competitor | KSh 67 | KSh 2,000 | 80 | 4–8 |
| **Total** | **KSh 667** | **KSh 20,000** | **800** | **22–44** |

**Assumptions:**
- Blended CPC: KSh 25 (Kenya average for business software)
- Click-to-trial rate: 3–5% (B2B SaaS Search benchmark)
- Trial-to-paid rate: 25–30% (with intro offers)

**Expected paying customers per month:** 6–13 (from 22–44 trials × 25–30% conversion)

### 5.2 Month 4–6: KSh 40,000/month

| Campaign | Daily Budget | Monthly Budget | Rationale |
|----------|-------------|----------------|-----------|
| Sector-Specific POS | KSh 800 | KSh 24,000 | Scale the top-performing sectors |
| Generic Business Software | KSh 267 | KSh 8,000 | Double the budget |
| Performance Max (new) | KSh 200 | KSh 6,000 | Start PMax with small budget |
| Branded & Competitor | KSh 67 | KSh 2,000 | Keep steady |
| **Total** | **KSh 1,334** | **KSh 40,000** | |

### 5.3 Month 7–9: KSh 60,000/month

| Campaign | Monthly Budget | Rationale |
|----------|---------------|-----------|
| Sector-Specific POS | KSh 30,000 | Add new sub-sectors |
| Generic Business Software | KSh 12,000 | Scale proven keywords |
| Performance Max | KSh 12,000 | Double PMax budget |
| Branded & Competitor | KSh 3,000 | Expand competitor list |
| Demand Gen (new) | KSh 3,000 | Start awareness campaigns |
| **Total** | **KSh 60,000** | |

### 5.4 Month 10–12: KSh 80,000/month

| Campaign | Monthly Budget | Rationale |
|----------|---------------|-----------|
| Sector-Specific POS | KSh 36,000 | Market dominance |
| Generic Business Software | KSh 16,000 | Full scale |
| Performance Max | KSh 16,000 | Scale what works |
| Branded & Competitor | KSh 6,000 | Protect brand |
| Demand Gen | KSh 6,000 | Brand awareness |
| **Total** | **KSh 80,000** | |

---

## 6. Bidding Strategy

### 6.1 Month 1–2: Manual CPC

**Why:** You have no conversion data yet. Google's smart bidding needs at least 15–30 conversions in the last 30 days to optimize effectively.

**How:**
- Set max CPC bids per keyword based on Kenya averages:
  - High-intent exact match: KSh 30–40
  - Phrase match: KSh 20–30
  - Broad match: KSh 15–20
- Review search terms report weekly
- Increase bids on converting keywords
- Decrease bids on non-converting keywords
- Add negative keywords for irrelevant searches

### 6.2 Month 3+: Maximize Conversions

**Why:** By Month 3, you should have 30–60 conversions. Google's algorithm can now optimize toward trial sign-ups.

**How:**
- Switch to "Maximize Conversions" bidding
- Set a target CPA (cost per acquisition) of KSh 150–200 per trial sign-up
- Let Google adjust bids automatically
- Review performance weekly, not daily

### 6.3 Month 6+: Target CPA

**Why:** With 100+ conversions, Google can reliably predict which clicks will convert.

**How:**
- Set target CPA = KSh 133 (realistic CAC from financial projections)
- Google will bid aggressively on high-converting traffic and conservatively on low-converting traffic
- Monitor closely for the first 2 weeks after switching

---

## 7. Conversion Tracking Setup

### 7.1 Conversions to Track

| Conversion Action | Value | Count | Window |
|------------------|-------|-------|--------|
| **Trial Sign-up** | KSh 0 | One per click | 30 days |
| **First Payment (Paid Customer)** | Plan price (KSh 1,000–5,500) | One per click | 30 days |
| **M-Pesa STK Initiated** | KSh 0 | One per click | 7 days |
| **Demo Login** | KSh 0 | One per click | 7 days |
| **Contact Form Submit** | KSh 0 | One per click | 14 days |

### 7.2 Implementation

**Step 1: Install Google Ads tag on every page**

Add this to your frontend `<head>`:

```html
<!-- Google Ads Conversion Tracking -->
<script async src="https://www.googletagmanager.com/gtag/js?id=AW-XXXXXXXXXX"></script>
<script>
  window.dataLayer = window.dataLayer || [];
  function gtag(){dataLayer.push(arguments);}
  gtag('js', new Date());
  gtag('config', 'AW-XXXXXXXXXX');
</script>
```

**Step 2: Track trial sign-up**

When a user completes signup, fire this event:

```javascript
gtag('event', 'conversion', {
  'send_to': 'AW-XXXXXXXXXX/XXXXXXXXXXXXXXXX',
  'value': 0.0,
  'currency': 'KES'
});
```

**Step 3: Track first payment**

When a user completes their first payment, fire this event:

```javascript
gtag('event', 'conversion', {
  'send_to': 'AW-XXXXXXXXXX/YYYYYYYYYYYYYYYY',
  'value': paymentAmount,
  'currency': 'KES'
});
```

**Step 4: UTM parameter capture**

In your backend, capture Google Ads UTM parameters during signup:

```
gclid (Google Click ID) — auto-appended by Google Ads
utm_source (e.g., "google")
utm_medium (e.g., "cpc")
utm_campaign (e.g., "restaurant-pos-kenya")
utm_content (e.g., "ad-headline-1")
```

Store these in the `tenants` table alongside the existing `fbclid` field in `Acquisition.clean()`.

### 7.3 Google Ads Account Setup Checklist

- [ ] Create Google Ads account
- [ ] Verify business address (Kenya)
- [ ] Set currency to KES
- [ ] Install Google Ads tag on all pages
- [ ] Define conversion actions (trial sign-up, first payment)
- [ ] Import Google Analytics 4 (if available)
- [ ] Set up Search Terms report
- [ ] Add negative keyword list
- [ ] Create ad extensions (sitelinks, callouts, structured snippets)
- [ ] Set up ad scheduling (business hours: 7 AM – 10 PM EAT)
- [ ] Set up location targeting (Kenya, with focus on Nairobi, Mombasa, Kisumu, Nakuru)

---

## 8. Landing Page Alignment

### 8.1 The Golden Rule: Ad → Landing Page Match

Every ad group must send traffic to a landing page that matches the ad's message. If the ad says "Restaurant POS", the landing page must be the restaurant sector page — not the home page.

| Ad Group | Landing Page | Why |
|----------|-------------|-----|
| Restaurant POS | `biznez.com/sectors/restaurant` | Hero says "Restaurant POS", features show table ordering |
| Retail POS | `biznez.com/sectors/retail` | Hero says "Retail POS", features show stock tracking |
| Generic POS | `biznez.com` | Home page shows all sectors |
| Competitor | `biznez.com/compare` | Comparison page shows why BizNez is better |
| Pricing | `biznez.com/pricing` | Pricing page shows plans and intro offers |

### 8.2 Landing Page Elements That Convert

Each sector landing page must have:

1. **Hero headline** that matches the ad keyword. If the ad says "Restaurant POS Kenya", the hero says "Restaurant POS Software in Kenya".
2. **Intro offer badge** — "First Month KSh 1,000" — visible above the fold.
3. **Single CTA** — "Start Your Free 7-Day Trial" — prominent, above the fold, repeated at the bottom.
4. **Feature cards** — 3–6 features specific to that sector (only features that are built).
5. **Social proof** — "Trusted by X Kenyan businesses" (add when you have numbers).
6. **FAQ section** — answers common objections.
7. **Pricing preview** — show the Lite plan price.
8. **No navigation distractions** — minimize menu links on landing pages to keep focus on the CTA.

---

## 9. Expected Performance Metrics

### 9.1 Month 1 Benchmarks (KSh 20,000 budget)

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| **Clicks** | 600 | 800 | 1,000 |
| **Average CPC** | KSh 33 | KSh 25 | KSh 20 |
| **Click-to-Trial Rate** | 3% | 5% | 7% |
| **Trial Sign-ups** | 18 | 40 | 70 |
| **Trial-to-Paid Rate** | 25% | 30% | 40% |
| **Paying Customers** | 5 | 12 | 28 |
| **Cost Per Trial** | KSh 1,111 | KSh 500 | KSh 286 |
| **Cost Per Paying Customer (CAC)** | KSh 4,000 | KSh 1,667 | KSh 714 |

**Note:** The financial projections use a blended CPC of KSh 25 (realistic) and a click-to-paid rate of 7.5% (which implies 5% click-to-trial × 30% trial-to-paid). The table above breaks these into two stages.

### 9.2 How to Improve Each Metric

| Metric | How to Improve |
|--------|---------------|
| **CPC** | Improve Quality Score: better ad copy, faster landing pages, more relevant keywords |
| **Click-to-Trial** | Better landing pages, reduce signup friction, add social proof, highlight intro offer |
| **Trial-to-Paid** | Engagement-based nudges, intro offers, automated M-Pesa STK, feature-driven upgrade prompts |
| **CAC** | All of the above + negative keywords to reduce waste |

### 9.3 Quality Score Impact

Google's Quality Score (1–10) directly affects your CPC:

| Quality Score | CPC Impact |
|--------------|-----------|
| 1–3 | 50–100% higher CPC |
| 4–6 | Average CPC |
| 7–8 | 20–30% lower CPC |
| 9–10 | 50%+ lower CPC |

**How to improve Quality Score:**
1. **Expected CTR:** Write compelling ad copy with clear CTAs and relevant keywords.
2. **Ad Relevance:** Each ad group targets a tight set of keywords. Don't mix "restaurant POS" with "salon POS" in the same ad group.
3. **Landing Page Experience:** Fast load times, mobile-friendly, relevant content, clear CTA.

---

## 10. Performance Max (Phase 2 — Month 4+)

### 10.1 When to Start PMax

Start Performance Max when:
- You have 50+ conversions (trial sign-ups) in the last 30 days
- Your Search campaigns are stable and profitable
- You want to scale beyond Search's reach

### 10.2 PMax Setup for BizNez

| Component | Details |
|-----------|---------|
| **Goal** | Maximize conversions (trial sign-ups) |
| **Budget** | KSh 6,000/month (Month 4–6), scaling to KSh 16,000/month |
| **Assets** | 5 headlines, 5 descriptions, 4 images, 1 logo, 1 business name |
| **Audience signal** | Upload customer email list (if available) as a seed audience |
| **Landing page** | `biznez.com` (home page) — PMax sends traffic to multiple pages automatically |
| **Negative keywords** | Apply the same negative keyword list as Search campaigns |

### 10.3 PMax Reality Check

**PMax will look cheap at first.** CPC will be KSh 5–15 because it spends heavily on Display and YouTube inventory where clicks are cheap. But the conversion rate will be 0.64% (Display) vs 3–5% (Search).

**Don't be fooled by low CPC.** Judge PMax by cost per paying customer, not cost per click.

**PMax needs data.** It will underperform for the first 2–4 weeks while it learns. Give it at least 50 conversions before judging.

---

## 11. Demand Gen (Phase 3 — Month 7+)

### 11.1 When to Start Demand Gen

Start Demand Gen when:
- You have brand recognition (people search for "BizNez")
- Search and PMax are running profitably
- You want to build awareness among people who aren't actively searching

### 11.2 Demand Gen Setup

| Component | Details |
|-----------|---------|
| **Placements** | YouTube (short videos), Discover (image cards), Gmail (promotional tab) |
| **Budget** | KSh 3,000/month (Month 7–9), scaling to KSh 6,000/month |
| **Creative** | Short videos showing BizNez in action (restaurant, retail, salon) |
| **Audience** | Lookalike of your paying customers |
| **Goal** | Brand awareness + trial sign-ups |

### 11.3 Demand Gen Reality Check

**Expect very low conversion rates.** 0.14–0.30% is normal for B2B SaaS. This is an awareness play, not a direct-response play.

**Value is in retargeting.** People who see your Demand Gen ad are more likely to convert when they later search for "POS Kenya" on Search.

---

## 12. Month-by-Month Optimization Plan

### Month 1: Foundation

- [ ] Set up Google Ads account
- [ ] Install conversion tracking
- [ ] Launch Search campaigns (all 3 campaigns)
- [ ] Use Manual CPC bidding
- [ ] Review search terms report daily for the first week
- [ ] Add negative keywords weekly
- [ ] **Goal:** Collect data, not optimize

### Month 2: First Optimization

- [ ] Identify top-converting ad groups (by trial sign-ups)
- [ ] Increase budget for top performers by 20%
- [ ] Decrease budget for bottom performers by 20%
- [ ] Pause keywords with 0 conversions after 100+ clicks
- [ ] Write new ad copy variations for A/B testing
- [ ] **Goal:** Reduce CPC by 10% through Quality Score improvement

### Month 3: Scale What Works

- [ ] Switch to Maximize Conversions bidding
- [ ] Double down on the top 2–3 sectors
- [ ] Add new long-tail keywords based on search terms report
- [ ] Review landing page performance (bounce rate, time on page)
- [ ] **Goal:** Achieve KSh 150 cost per trial sign-up

### Month 4: Add Performance Max

- [ ] Launch PMax with KSh 6,000/month budget
- [ ] Keep Search campaigns running at full budget
- [ ] Monitor PMax learning phase (2–4 weeks)
- [ ] **Goal:** PMax contributes 20% of total conversions

### Month 5–6: Optimize PMax

- [ ] Review PMax channel performance (Search vs Display vs YouTube)
- [ ] Add asset group variations for different sectors
- [ ] Refine audience signals
- [ ] **Goal:** PMax cost per trial within 20% of Search cost per trial

### Month 7–9: Add Demand Gen

- [ ] Launch Demand Gen with KSh 3,000/month budget
- [ ] Create video assets (30-second demos for each sector)
- [ ] Build lookalike audiences from paying customers
- [ ] **Goal:** Brand search volume increases by 30%

### Month 10–12: Full Scale

- [ ] Scale all campaigns to KSh 80,000/month total
- [ ] Test Target CPA bidding on Search
- [ ] Expand to new sectors and sub-sectors
- [ ] Review Year 1 performance and plan Year 2 strategy
- [ ] **Goal:** Maintain KSh 133 CAC at scale

---

## 13. Google Ads vs Meta Ads: Recommended Mix

| Phase | Google | Meta | Why |
|-------|--------|------|-----|
| **Month 1–3** | 100% | 0% | Google Search validates conversion rates first. Meta CAPI is already built, but Google intent is higher. |
| **Month 4–6** | 70% | 30% | Add Meta for retargeting and broader reach. Google remains primary. |
| **Month 7–12** | 60% | 40% | Meta scales better for volume. Google remains the high-intent backbone. |

**Why Google first:**
- A person searching "restaurant POS Kenya" is ready to buy. A person scrolling Facebook is not.
- Google Search has a 3–5% click-to-trial rate. Meta has a 1–2% click-to-trial rate.
- With KSh 20,000/month, you need the highest conversion rate possible. Google delivers it.

**When to add Meta:**
- When you have enough data to build lookalike audiences
- When you want to retarget people who visited but didn't sign up
- When you want to reach people who aren't actively searching but might be interested

---

## 14. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **CPC higher than KSh 25** | Fewer clicks, fewer trials | Improve Quality Score, use more long-tail keywords, add negative keywords |
| **Landing page bounce rate > 70%** | Wasted clicks | Improve landing page content, add social proof, reduce signup friction |
| **Low trial-to-paid conversion** | High CAC | Implement intro offers, engagement-based nudges, automated M-Pesa STK |
| **PMax underperforms** | Wasted budget on display traffic | Wait 4 weeks for learning, then reduce budget if CPA is 2× Search CPA |
| **Brevo email not configured** | Can't send trial nudges | Configure Brevo before launching ads |
| **Conversion tracking broken** | Can't measure ROI | Test all conversion events before launching ads |
| **Competitor bidding on your brand** | Higher CPC for brand keywords | Monitor search terms, increase brand keyword bids |

---

## 15. Post-Launch Prerequisites

Before launching Google Ads, these must be in place:

1. **Google Ads account** — created and verified with Kenya business address.
2. **Domain verified** — `biznez.com` (or chosen domain) verified in Google Ads.
3. **Conversion tracking installed** — Google Ads tag on all pages, conversion events firing correctly.
4. **Landing pages live** — at least the top 20 sub-sector pages with full content.
5. **Intro offers configured** — KSh 1,000 (Lite), KSh 2,500 (Growth), KSh 5,500 (Pro) visible on pricing page.
6. **Automated M-Pesa STK working** — one-tap payment for plan activation.
7. **Brevo email configured** — trial nudges and welcome emails sending correctly.
8. **Google Analytics 4** — installed and linked to Google Ads.
9. **Google Search Console** — site submitted and indexed.
10. **UTM parameter capture** — `gclid` and UTM params stored in the database during signup.

---

## 16. Summary: What to Do When

| Timeline | Action | Budget |
|----------|--------|--------|
| **Before launch** | Set up Google Ads account, conversion tracking, landing pages | KSh 0 |
| **Month 1** | Launch Search campaigns (3 campaigns, 9 ad groups), Manual CPC | KSh 20,000 |
| **Month 2** | Optimize: pause bad keywords, scale good ones, improve Quality Score | KSh 20,000 |
| **Month 3** | Switch to Maximize Conversions bidding, double down on top sectors | KSh 20,000 |
| **Month 4** | Add Performance Max, scale Search budget | KSh 40,000 |
| **Month 5–6** | Optimize PMax, refine audience signals | KSh 40,000 |
| **Month 7** | Add Demand Gen, create video assets | KSh 60,000 |
| **Month 8–9** | Scale all campaigns, test Target CPA | KSh 60,000 |
| **Month 10–12** | Full scale, expand to new sectors | KSh 80,000 |

**Expected outcome (realistic scenario):**
- Month 1: 12 paying customers, KSh 97,633 net profit
- Month 3: 172 total customers, KSh 672,930 net profit
- Month 12: 1,517 total customers, KSh 6,791,813 net profit
- Year 1 total net profit: ~KSh 27.2 million
