# BizNez: Growth Plan — Financial Projections & Google Ads Strategy

**Written:** 2026-11-12
**Status:** Planning document — for implementation session reference
**Source documents:** `revised-financial-projections.md`, `google-ads-strategy.md`

This document consolidates the financial projections and Google Ads strategy into one reference for the implementation session. It covers the revenue model, month-by-month projections, ad campaign structure, keyword strategy, conversion tracking, and implementation prerequisites.

---

## Part 1: Financial Projections

### 1.1 Pricing Plans

| Plan | KSh/month | Yearly (KSh) | Staff | Branches | Items |
|------|-----------|-------------|-------|----------|-------|
| **Lite** | 2,650 | 26,500 | 3 | 1 | 500 |
| **Growth** | 4,500 | 45,000 | 8 | 3 | 5,000 |
| **Pro** | 9,000 | 90,000 | 30 | 10 | Unlimited |

**Intro offers (first month only):**

| Plan | Regular Price | Intro Offer | Savings |
|------|--------------|-------------|---------|
| **Lite** | KSh 2,650 | KSh 1,000 | KSh 1,650 (62% off) |
| **Growth** | KSh 4,500 | KSh 2,500 | KSh 2,000 (44% off) |
| **Pro** | KSh 9,000 | KSh 5,500 | KSh 3,500 (39% off) |

**ETR add-on:** KSh 4 per ETR sent via email, billed monthly (Pro only).

### 1.2 Plan Mix Assumption

| Plan | Percentage | Rationale |
|------|-----------|-----------|
| Lite | 50% | Entry point, but feature gaps drive upgrades to Growth |
| Growth | 40% | Commissions, ERP reports, CRM pipeline, table ordering are strong triggers |
| Pro | 10% | Multi-branch, audit log, ETR — niche but high-value |

**Weighted ARPU (steady state):** KSh 4,025/month per paying customer
**Weighted ARPU (Month 1, with intro offers):** KSh 2,050/month per new customer

### 1.3 Conversion Funnel

| Stage | Conservative | Realistic | Optimistic |
|-------|-------------|-----------|-----------|
| Click-to-Trial Sign-up Rate | 15% | 25% | 35% |
| Trial-to-Paid Conversion Rate | 20% | 30% | 45% |
| Overall Click-to-Paid Rate | 3.0% | 7.5% | 15.75% |

### 1.4 Customer Churn (Differential by Plan)

| Plan | Conservative | Realistic | Optimistic |
|------|-------------|-----------|-----------|
| **Lite** | 10% | 6% | 4% |
| **Growth** | 6% | 4% | 2% |
| **Pro** | 4% | 2% | 1% |
| **Weighted average** | **7.0%** | **4.4%** | **2.6%** |

**Average Customer Lifetime:** 4.3 months (conservative), 6.8 months (realistic), 12.0 months (optimistic)

### 1.5 Customer Lifetime Value (LTV)

| Scenario | ARPU (KSh) | Lifetime (months) | LTV (KSh) |
|----------|-----------|-------------------|-----------|
| Conservative | 4,025 | 4.3 | 17,308 |
| Realistic | 4,025 | 6.8 | 27,370 |
| Optimistic | 4,025 | 12.0 | 48,300 |

### 1.6 Customer Acquisition Cost (CAC)

| Scenario | Click-to-Paid Rate | CAC per Customer (KSh) |
|----------|-------------------|----------------------|
| Conservative | 3.0% | 333 |
| Realistic | 7.5% | 133 |
| Optimistic | 15.75% | 63 |

**CAC Payback Period:** 1 day (realistic) — industry benchmark is 12–18 months.

### 1.7 Customer Upgrade Paths

| Upgrade Path | Monthly Rate | Trigger |
|--------------|-------------|---------|
| **Lite → Growth** | 15% per month | Need commissions, ERP reports, CRM pipeline, table ordering, loyalty |
| **Growth → Pro** | 5% per month | Need multi-branch, audit log, ETR, all 3 products |

**Impact:** By Month 12, upgrades increase ARPU by 12.8% (from KSh 4,025 to KSh 4,540). Revenue from upgrades alone: KSh 785,330/month by Month 12.

### 1.8 Infrastructure & Operating Costs

| Cost Item | Monthly Cost (KSh) | Notes |
|-----------|-------------------|-------|
| Contabo VPS (Cloud VPS 3) | ~3,500 | 8 vCPU, 30 GB RAM, PostgreSQL + Spring Boot |
| Cloudflare R2 (menu images) | ~200 | ~100 GB storage, minimal egress |
| Brevo email (trial + paid) | ~500–1,500 | Free tier up to 3,000 emails/day |
| Domain registration | ~167 | KSh 2,000/year amortized monthly |
| **Total fixed costs** | **~4,367–5,367** | Before ads and human labor |

---

### 2. Month-by-Month Projections (Realistic Scenario)

#### 2.1 Months 1–3 (KSh 20,000/month ad budget)

| Month | Ad Spend | New Customers | Total Customers | MRR | Net Profit |
|-------|----------|--------------|-----------------|-----|------------|
| **1** | KSh 20,000 | 60 | 60 | KSh 123,000 | **KSh 97,633** |
| **2** | KSh 20,000 | 60 | 117 | KSh 353,250 | **KSh 317,883** |
| **3** | KSh 20,000 | 60 | 172 | KSh 708,296 | **KSh 672,930** |

**First 3 months summary:** Total ad spend KSh 60,000. Total net profit KSh 1,088,446.

#### 2.2 Months 4–12 (Scaling ad budget)

| Month | Ad Spend | New Customers | Total Customers | Effective ARPU | MRR | Net Profit |
|-------|----------|--------------|-----------------|----------------|-----|------------|
| 4 | 40,000 | 120 | 284 | KSh 4,200 | KSh 1,192,800 | KSh 1,147,433 |
| 5 | 40,000 | 120 | 391 | KSh 4,280 | KSh 1,673,480 | KSh 1,628,113 |
| 6 | 40,000 | 120 | 494 | KSh 4,350 | KSh 2,148,900 | KSh 2,093,533 |
| 7 | 60,000 | 180 | 652 | KSh 4,400 | KSh 2,868,800 | KSh 2,793,433 |
| 8 | 60,000 | 180 | 803 | KSh 4,450 | KSh 3,573,350 | KSh 3,507,983 |
| 9 | 60,000 | 180 | 948 | KSh 4,480 | KSh 4,247,040 | KSh 4,171,673 |
| 10 | 80,000 | 240 | 1,146 | KSh 4,500 | KSh 5,157,000 | KSh 5,061,633 |
| 11 | 80,000 | 240 | 1,336 | KSh 4,520 | KSh 6,038,720 | KSh 5,943,353 |
| 12 | 80,000 | 240 | 1,517 | KSh 4,540 | KSh 6,887,180 | KSh 6,791,813 |

#### 2.3 Year 1 Summary (Realistic)

| Metric | Value |
|--------|-------|
| **Total ad spend (Year 1)** | KSh 600,000 |
| **Total paying customers by Month 12** | 1,517 |
| **MRR by Month 12** | KSh 6,887,180 |
| **ARR by Month 12** | KSh 82,646,160 |
| **Total net profit (Year 1, before labor)** | ~KSh 27,200,000 |
| **Average monthly net profit (Year 1)** | ~KSh 2,266,667 |
| **Effective CAC (Year 1)** | KSh 395 |
| **LTV:CAC ratio** | 69.3:1 (exceptional) |

---

### 3. Three Scenarios Comparison

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| **Customers by Month 12** | 508 | 1,517 | 3,801 |
| **MRR by Month 12** | KSh 2,032,400 | KSh 6,887,180 | KSh 15,456,540 |
| **Total net profit (Year 1)** | ~KSh 9,500,000 | ~KSh 27,200,000 | ~KSh 74,000,000 |
| **Effective CAC** | KSh 1,181 | KSh 395 | KSh 158 |
| **LTV:CAC ratio** | 14.6:1 | 69.3:1 | 305.7:1 |

---

### 4. Intro Offer Impact

Intro offers reduce Month 1 revenue by ~49% but increase trial-to-paid conversion from 25% to 30%. The larger customer base, higher upgrade rates, and lower churn more than compensate from Month 3 onward.

| Timeframe | Without Intro | With Intro | Winner |
|-----------|--------------|------------|--------|
| Month 1 | KSh 192,000 | KSh 123,000 | Without (-36%) |
| Month 2 | KSh 384,000 | KSh 353,250 | Without (-8%) |
| Month 3 | KSh 576,000 | KSh 578,000+ | **With** |
| Month 6 | KSh 1,152,000 | KSh 1,250,000+ | **With** (+8.5%) |
| Month 12 | KSh 2,304,000 | KSh 2,600,000+ | **With** (+12.8%) |

---

### 5. Key Financial Metrics

| Metric | Value | Industry Benchmark |
|--------|-------|-------------------|
| **LTV:CAC ratio** | 69.3:1 | 3:1 is healthy |
| **CAC payback** | 1 day | 12–18 months |
| **Break-even** | 5 customers at KSh 20K ads | — |

**You're profitable from Day 1.** Even in the conservative scenario, Month 1 profit is KSh 23,833.

---

## Part 2: Google Ads Strategy

### 6. Campaign Types to Use

| Campaign Type | Use for BizNez? | Why |
|--------------|----------------|-----|
| **Search** | ✅ **Primary** | High-intent buyers. Best conversion rate (3–5% for SaaS). |
| **Performance Max** | ✅ **Phase 2** | Scales volume. Needs 50+ conversions/month. Start Month 4+. |
| **Demand Gen** | ⚠️ **Phase 3** | Awareness (YouTube, Discover). Start Month 7+. |
| Display, Video, Shopping, App | ❌ Skip | Not relevant for B2B SaaS at this stage. |

**Phased rollout:**
- **Months 1–3:** 100% Search campaigns.
- **Months 4–6:** 70% Search, 30% Performance Max.
- **Months 7–12:** 60% Search, 30% Performance Max, 10% Demand Gen.

---

### 7. Search Campaign Structure

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

#### 7.1 Campaign 1: Sector-Specific POS (KSh 14,000/month at KSh 20K budget)

Each ad group targets a specific sector and sends traffic to that sector's landing page.

| Ad Group | Keywords (examples) | Landing Page | Ad Headline 1 |
|----------|-------------------|-------------|---------------|
| Restaurant POS | "restaurant POS Kenya", "table ordering system Kenya" | `biznez.com/sectors/restaurant` | Restaurant POS Kenya — From KSh 1,000 First Month |
| Retail POS | "retail POS Kenya", "shop billing software" | `biznez.com/sectors/retail` | Retail POS Kenya — Stock, Sales, M-Pesa |
| Salon/Barber POS | "salon management software Kenya", "barber POS system" | `biznez.com/sectors/salon` | Salon POS Kenya — Commissions, Bookings, M-Pesa |
| Hotel POS | "hotel management system Kenya", "guest house software" | `biznez.com/sectors/hotel` | Hotel POS Kenya — Guest Management, M-Pesa |
| Hardware/Wholesale | "hardware shop POS Kenya", "wholesale billing software" | `biznez.com/sectors/wholesale` | Hardware Shop POS — Customer Statements, Pack & Loose |
| Service Business | "service business software Kenya", "car wash management" | `biznez.com/sectors/services` | Service Business POS — Bookings, M-Pesa |
| Tour/Property | "tour company software Kenya", "property management Nairobi" | `biznez.com/sectors/tours` | Tour & Property POS — Bookings, M-Pesa |

#### 7.2 Campaign 2: Generic Business Software (KSh 4,000/month)

| Ad Group | Keywords | Landing Page |
|----------|---------|-------------|
| POS Software Kenya | "POS software Kenya", "best POS system Kenya", "POS with M-Pesa" | `biznez.com` |
| Business Management Software | "business management software Kenya", "SME software Kenya" | `biznez.com` |
| M-Pesa Integration Software | "M-Pesa integration software", "STK prompt software Kenya" | `biznez.com/pricing` |

#### 7.3 Campaign 3: Branded & Competitor (KSh 2,000/month)

| Ad Group | Keywords | Landing Page |
|----------|---------|-------------|
| BizNez Brand | "BizNez", "BizNez POS", "BizNez login" | `biznez.com` |
| Competitor Alternatives | "Odoo POS alternative Kenya", "ERPNext alternative Kenya" | `biznez.com/compare` |

---

### 8. Keyword Strategy

#### 8.1 Match Types

| Match Type | Example | When It Triggers |
|-----------|---------|------------------|
| **Exact** | `[restaurant POS Kenya]` | Only "restaurant POS Kenya" |
| **Phrase** | `"restaurant POS Kenya"` | "best restaurant POS Kenya", "restaurant POS Kenya price" |
| **Broad** | `restaurant POS Kenya` | "POS for restaurants in Nairobi" |

**Start with phrase match.** After 2 weeks, move top-converting keywords to exact match.

#### 8.2 Negative Keywords (Add Immediately)

| Category | Negative Keywords |
|----------|-----------------|
| **Free/cheap** | "free POS", "open source POS", "download POS" |
| **Jobs** | "POS technician", "POS repair", "POS installer job" |
| **Hardware** | "POS printer", "POS machine", "barcode scanner" |
| **Not Kenya** | "USA", "UK", "Canada", "Australia", "India" |
| **Not SaaS** | "POS tutorial", "how to build POS", "POS course" |

#### 8.3 Long-Tail Keywords (Your Secret Weapon)

Your 59 sub-sector landing pages are designed for these:

| Long-Tail Keyword | Monthly Searches | CPC (KSh) |
|------------------|-----------------|-----------|
| "hardware shop POS Kenya" | 50–100 | 15–25 |
| "agrovet billing software" | 20–50 | 10–20 |
| "car wash management system Nairobi" | 30–60 | 15–30 |
| "butchery POS system Kenya" | 10–30 | 10–15 |
| "guest house software Mombasa" | 10–20 | 10–20 |

---

### 9. Ad Copy Principles

1. **Lead with the price.** "From KSh 1,000 First Month" in headline 1.
2. **Mention M-Pesa.** Every ad should reference M-Pesa.
3. **Sector-specific language.** Restaurant ad says "table ordering", retail ad says "stock tracking".
4. **Free trial CTA.** "7-Day Free Trial, No Card Needed".
5. **Social proof (when available).** "Trusted by X Kenyan businesses".

#### Ad Copy Examples

**Restaurant:**
> Restaurant POS Kenya — From KSh 1,000 First Month
> Table Ordering, Kitchen Display, M-Pesa
> QR codes per table. Kitchen sees orders instantly. Customers track & pay via M-Pesa.

**Retail:**
> Retail POS Kenya — Stock, Sales, M-Pesa
> From KSh 1,000 · 7-Day Free Trial
> Track stock, sales, and customer credit. Works on phone, tablet, desktop.

**Generic:**
> POS Software Kenya — From KSh 1,000
> M-Pesa, Offline, 59 Business Types
> BizNez by LeziTech — Legitimacy in Business Tech

---

### 10. Budget Allocation

| Period | Monthly Budget | Campaign Split |
|--------|---------------|----------------|
| Month 1–3 | KSh 20,000 | 70% sector, 20% generic, 10% branded |
| Month 4–6 | KSh 40,000 | Add Performance Max (KSh 6,000) |
| Month 7–9 | KSh 60,000 | Add Demand Gen (KSh 3,000) |
| Month 10–12 | KSh 80,000 | Full scale across all channels |

**Total Year 1 ad spend:** KSh 600,000

---

### 11. Bidding Strategy Evolution

| Timeline | Strategy | Why |
|----------|----------|-----|
| Month 1–2 | **Manual CPC** | No conversion data yet. Set bids: KSh 20–30 phrase, KSh 30–40 exact. |
| Month 3+ | **Maximize Conversions** | 30–60 conversions collected. Google can optimize toward trial sign-ups. |
| Month 6+ | **Target CPA** | 100+ conversions. Set target CPA = KSh 133 (realistic CAC). |

---

### 12. Conversion Tracking Setup

#### 12.1 Conversions to Track

| Conversion Action | Value | Count | Window |
|------------------|-------|-------|--------|
| **Trial Sign-up** | KSh 0 | One per click | 30 days |
| **First Payment (Paid Customer)** | Plan price (KSh 1,000–5,500) | One per click | 30 days |
| **M-Pesa STK Initiated** | KSh 0 | One per click | 7 days |

#### 12.2 Implementation

**Step 1: Install Google Ads tag on every page**

Add to frontend `<head>`:
```html
<script async src="https://www.googletagmanager.com/gtag/js?id=AW-XXXXXXXXXX"></script>
<script>
  window.dataLayer = window.dataLayer || [];
  function gtag(){dataLayer.push(arguments);}
  gtag('js', new Date());
  gtag('config', 'AW-XXXXXXXXXX');
</script>
```

**Step 2: Track trial sign-up**

When a user completes signup:
```javascript
gtag('event', 'conversion', {
  'send_to': 'AW-XXXXXXXXXX/XXXXXXXXXXXXXXXX',
  'value': 0.0,
  'currency': 'KES'
});
```

**Step 3: Track first payment**

When a user completes their first payment:
```javascript
gtag('event', 'conversion', {
  'send_to': 'AW-XXXXXXXXXX/YYYYYYYYYYYYYYYY',
  'value': paymentAmount,
  'currency': 'KES'
});
```

**Step 4: UTM parameter capture**

In the backend, capture during signup:
- `gclid` (Google Click ID) — auto-appended by Google Ads
- `utm_source`, `utm_medium`, `utm_campaign`, `utm_content`

Store these in the `tenants` table alongside the existing `fbclid` field in `Acquisition.clean()`.

---

### 13. Landing Page Alignment

**Golden Rule:** Every ad group must send traffic to a landing page that matches the ad's message.

| Ad Group | Landing Page |
|----------|-------------|
| Restaurant POS | `biznez.com/sectors/restaurant` |
| Retail POS | `biznez.com/sectors/retail` |
| Generic POS | `biznez.com` |
| Competitor | `biznez.com/compare` |
| Pricing | `biznez.com/pricing` |

**Each landing page must have:**
1. Hero headline matching the ad keyword
2. Intro offer badge — "First Month KSh 1,000" — above the fold
3. Single CTA — "Start Your Free 7-Day Trial"
4. Feature cards — 3–6 sector-specific features (only built features)
5. FAQ section
6. Pricing preview
7. No navigation distractions

---

### 14. Expected Month 1 Performance (KSh 20,000 budget)

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| **Clicks** | 600 | 800 | 1,000 |
| **Average CPC** | KSh 33 | KSh 25 | KSh 20 |
| **Trial Sign-ups** | 18 | 40 | 70 |
| **Paying Customers** | 5 | 12 | 28 |
| **Cost Per Customer (CAC)** | KSh 4,000 | KSh 1,667 | KSh 714 |

---

### 15. Google Ads vs Meta Ads: Recommended Mix

| Phase | Google | Meta | Why |
|-------|--------|------|-----|
| Month 1–3 | 100% | 0% | Google Search validates conversion rates first. |
| Month 4–6 | 70% | 30% | Add Meta for retargeting and lookalike audiences. |
| Month 7–12 | 60% | 40% | Meta scales better for volume. |

---

### 16. Month-by-Month Optimization Plan

| Month | Action | Goal |
|-------|--------|------|
| **1** | Launch Search campaigns, Manual CPC, review search terms daily | Collect data |
| **2** | Pause bad keywords, scale good ones, improve Quality Score | Reduce CPC by 10% |
| **3** | Switch to Maximize Conversions, double down on top sectors | KSh 150 cost per trial |
| **4** | Add Performance Max (KSh 6,000/month) | PMax contributes 20% of conversions |
| **5–6** | Optimize PMax, refine audience signals | PMax CPA within 20% of Search |
| **7–9** | Add Demand Gen (KSh 3,000/month), create video assets | Brand search volume +30% |
| **10–12** | Full scale, test Target CPA, expand to new sectors | Maintain KSh 133 CAC at scale |

---

### 17. Post-Launch Prerequisites

Before launching Google Ads, these must be in place:

1. **Google Ads account** — created and verified with Kenya business address.
2. **Domain verified** — `biznez.com` (or chosen domain) verified in Google Ads.
3. **Conversion tracking installed** — Google Ads tag on all pages, events firing correctly.
4. **Landing pages live** — at least the top 20 sub-sector pages with full content.
5. **Intro offers configured** — KSh 1,000 / 2,500 / 5,500 visible on pricing page.
6. **Automated M-Pesa STK working** — one-tap payment for plan activation.
7. **Brevo email configured** — trial nudges and welcome emails sending correctly.
8. **Google Analytics 4** — installed and linked to Google Ads.
9. **Google Search Console** — site submitted and indexed.
10. **UTM parameter capture** — `gclid` and UTM params stored in the database during signup.

---

### 18. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **CPC higher than KSh 25** | Fewer clicks, fewer trials | Improve Quality Score, use long-tail keywords |
| **Landing page bounce rate > 70%** | Wasted clicks | Improve content, add social proof, reduce signup friction |
| **Low trial-to-paid conversion** | High CAC | Intro offers, engagement-based nudges, automated M-Pesa STK |
| **Brevo email not configured** | Can't send trial nudges | Configure Brevo before launching ads |
| **Conversion tracking broken** | Can't measure ROI | Test all conversion events before launching |
| **P1 features not built** | High churn | Build till separation, ETR, restaurant ordering before scaling ads |

---

### 19. Summary: What to Expect

| Timeframe | Monthly Net Profit (Realistic) | Cumulative Profit |
|-----------|-------------------------------|-------------------|
| **Month 1** | KSh 97,633 | KSh 97,633 |
| **Month 3** | KSh 672,930 | KSh 1,088,446 |
| **Month 6** | KSh 2,093,533 | ~KSh 5,000,000 |
| **Month 12** | KSh 6,791,813 | ~KSh 27,200,000 |

**All figures are before human labor costs.** The bottleneck is your platform, not your ads. Build the P1 features (till separation, ETR, restaurant ordering, feature gating), configure the infrastructure (Brevo, PayHero, conversion tracking), and these projections are achievable.

**Key advantage:** Your CAC payback is measured in **days, not months**. Every KSh 1 spent on ads returns KSh 69+ over the customer's lifetime. This is an exceptionally efficient growth engine.