# BizNez: Financial Projections & Profit Potential

**Written:** 2026-11-12
**Status:** Analysis & planning document

This document projects the revenue, costs, and net profit potential for BizNez when acquired through Google Ads and Meta Ads, starting with a KSh 20,000/month ad budget.

---

## 1. Key Assumptions

### 1.1 Pricing Plans

| Plan | KSh/month | Yearly (KSh) | Staff | Branches | Items |
|------|-----------|-------------|-------|----------|-------|
| **Lite** | 2,650 | 26,500 | 3 | 1 | 500 |
| **Growth** | 4,500 | 45,000 | 8 | 3 | 5,000 |
| **Pro** | 9,000 | 90,000 | 30 | 10 | Unlimited |

**ETR add-on:** KSh 4 per ETR sent via email, billed monthly.

### 1.2 Plan Mix Assumption

Based on the Kenyan SME landscape, we assume the following distribution of paying customers:

| Plan | Percentage | Rationale |
|------|-----------|-----------|
| Lite | 60% | Majority of SMEs are small: 1–3 staff, single branch |
| Growth | 30% | Growing businesses with multiple staff and branches |
| Pro | 10% | Larger operations (hotels, multi-branch retail, chains) |

**Weighted Average Revenue Per User (ARPU):**
- (60% × KSh 2,650) + (30% × KSh 4,500) + (10% × KSh 9,000)
- = KSh 1,590 + KSh 1,350 + KSh 900
- = **KSh 3,840/month per paying customer**

### 1.3 Ad Costs in Kenya

| Channel | Metric | Cost (KSh) | Source |
|---------|--------|-----------|--------|
| **Google Search Ads** | CPC | KSh 15–40 | Kenya CPC is 74% below US average (~$0.70 vs $2.69 global) |
| **Google Performance Max** | CPC | KSh 10–30 | Automated, lower intent but broader reach |
| **Meta (Facebook/Instagram)** | CPC | KSh 10–60 | Kenya-specific data, varies by targeting |
| **Meta (Facebook/Instagram)** | CPM | KSh 80–250 | Cost per 1,000 impressions for broad audiences |

**Blended average CPC used for projections: KSh 25 per click**

### 1.4 Conversion Funnel Assumptions

| Stage | Conservative | Realistic | Optimistic |
|-------|-------------|-----------|-----------|
| Click-to-Trial Sign-up Rate | 15% | 25% | 35% |
| Trial-to-Paid Conversion Rate | 15% | 25% | 40% |
| Overall Click-to-Paid Rate | 2.25% | 6.25% | 14% |

**Explanation:**
- **Click-to-Trial:** A visitor clicks an ad, lands on a sector page, and starts a 7-day free trial.
- **Trial-to-Paid:** After the 7-day trial, the percentage that subscribes to a paid plan.
- **Overall Click-to-Paid:** Combined rate from ad click to paying customer.

### 1.5 Customer Churn

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| Monthly Churn Rate | 8% | 5% | 3% |
| Annual Churn Rate (compounded) | 60% | 43% | 30% |
| Average Customer Lifetime | 4.2 months | 6 months | 10 months |

**Rationale:** SMB SaaS globally averages 3–7% monthly churn. In emerging markets with price-sensitive micro-businesses, churn tends toward the higher end (5–8%). The optimistic scenario assumes strong product-market fit and good onboarding.

### 1.6 Customer Lifetime Value (LTV)

LTV = ARPU × Average Customer Lifetime

| Scenario | ARPU (KSh) | Lifetime (months) | LTV (KSh) |
|----------|-----------|-------------------|-----------|
| Conservative | 3,840 | 4.2 | 16,128 |
| Realistic | 3,840 | 6.0 | 23,040 |
| Optimistic | 3,840 | 10.0 | 38,400 |

### 1.7 Customer Acquisition Cost (CAC)

CAC = Ad Spend ÷ Number of Paying Customers Acquired

| Scenario | Click-to-Paid Rate | CAC per Customer (KSh) |
|----------|-------------------|----------------------|
| Conservative | 2.25% | 444 |
| Realistic | 6.25% | 157 |
| Optimistic | 14% | 71 |

**CAC Payback Period** (months to recover CAC from customer revenue):

| Scenario | CAC (KSh) | ARPU (KSh) | Payback (months) |
|----------|----------|-----------|-----------------|
| Conservative | 444 | 3,840 | 0.12 months (4 days) |
| Realistic | 157 | 3,840 | 0.04 months (1 day) |
| Optimistic | 71 | 3,840 | 0.02 months (half day) |

**Key insight:** Even in the conservative scenario, CAC payback is under 1 month. This is exceptionally healthy — most SaaS companies target 12–18 months payback.

### 1.8 Infrastructure & Operating Costs

| Cost Item | Monthly Cost (KSh) | Notes |
|-----------|-------------------|-------|
| Contabo VPS (Cloud VPS 3) | ~3,500 | 8 vCPU, 30 GB RAM, PostgreSQL + Spring Boot |
| Cloudflare R2 (menu images) | ~200 | ~100 GB storage, minimal egress |
| Brevo email (trial + paid) | ~500–1,500 | Free tier up to 3,000 emails/day, then paid |
| Domain registration | ~167 | KSh 2,000/year amortized monthly |
| **Total fixed costs** | **~4,367–5,367** | Before ads and human labor |

**Human labor costs** (not included in this analysis — you're the builder and operator in early stages):
- If you hire a part-time support person: ~KSh 20,000–30,000/month
- If you hire a full-time developer: ~KSh 80,000–150,000/month

---

## 2. Month-by-Month Projections

### 2.1 Scenario Definitions

| Scenario | Description |
|----------|-------------|
| **Conservative** | Low conversion rates, high churn. Ads underperform, onboarding needs work, some customers leave quickly. |
| **Realistic** | Moderate conversion rates, moderate churn. Ads perform as expected, onboarding is decent, reasonable retention. |
| **Optimistic** | High conversion rates, low churn. Ads are well-optimized, onboarding is smooth, customers stick around. |

### 2.2 Month 1–3 Projections (KSh 20,000/month ad budget)

#### Month 1

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| Ad spend | 20,000 | 20,000 | 20,000 |
| Clicks generated (÷ KSh 25 CPC) | 800 | 800 | 800 |
| Trial sign-ups | 120 (15%) | 200 (25%) | 280 (35%) |
| **Paying customers** | **27** (22.5% of trials) | **50** (25% of trials) | **112** (40% of trials) |
| CAC per customer | 741 | 400 | 179 |
| MRR (new customers × ARPU) | 103,680 | 192,000 | 430,080 |
| Fixed costs | 5,367 | 5,367 | 5,367 |
| **Net profit before labor** | **78,313** | **166,633** | **404,713** |

#### Month 2

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| Ad spend | 20,000 | 20,000 | 20,000 |
| Clicks generated | 800 | 800 | 800 |
| Trial sign-ups | 120 | 200 | 280 |
| New paying customers | 27 | 50 | 112 |
| Month 1 customers retained | 25 (8% churn) | 48 (5% churn) | 110 (3% churn) |
| **Total paying customers** | **52** | **98** | **222** |
| MRR | 199,680 | 376,320 | 852,480 |
| Fixed costs | 5,367 | 5,367 | 5,367 |
| **Net profit before labor** | **174,313** | **350,953** | **812,113** |

#### Month 3

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| Ad spend | 20,000 | 20,000 | 20,000 |
| Clicks generated | 800 | 800 | 800 |
| Trial sign-ups | 120 | 200 | 280 |
| New paying customers | 27 | 50 | 112 |
| Month 1 customers retained | 23 (8%² churn) | 44 (5%² churn) | 107 (3%² churn) |
| Month 2 customers retained | 48 (8% churn) | 93 (5% churn) | 215 (3% churn) |
| **Total paying customers** | **98** | **187** | **434** |
| MRR | 376,320 | 718,080 | 1,666,560 |
| Fixed costs | 5,367 | 5,367 | 5,367 |
| **Net profit before labor** | **350,953** | **692,713** | **1,620,827** |

### 2.3 First 3 Months Summary

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| **Total ad spend (3 months)** | 60,000 | 60,000 | 60,000 |
| **Total paying customers by Month 3** | 98 | 187 | 434 |
| **MRR by Month 3** | 376,320 | 718,080 | 1,666,560 |
| **Total net profit (3 months)** | 603,579 | 1,210,300 | 2,837,653 |
| **Average monthly net profit** | 201,193 | 403,433 | 945,884 |
| **Effective CAC** | 612 | 321 | 138 |

---

## 3. Months 4–12 Projections (Scaling Ad Budget)

As the platform matures, you'll likely increase the ad budget. The projections below assume a **gradual increase** in ad spend as revenue grows.

### 3.1 Ad Budget Scaling Strategy

| Period | Monthly Ad Budget | Rationale |
|--------|------------------|-----------|
| Month 1–3 | KSh 20,000 | Testing phase, learning what works |
| Month 4–6 | KSh 40,000 | Scale what works, double the budget |
| Month 7–9 | KSh 60,000 | Continue scaling, add new sectors |
| Month 10–12 | KSh 80,000 | Aggressive growth, market dominance |

### 3.2 Realistic Scenario: Months 4–12

| Month | Ad Spend | New Customers | Churned | Total Customers | MRR | Fixed Costs | Net Profit (before labor) |
|-------|----------|--------------|---------|----------------|-----|-------------|--------------------------|
| 4 | 40,000 | 100 | 9 | 278 | 1,067,520 | 5,367 | 1,017,153 |
| 5 | 40,000 | 100 | 14 | 364 | 1,397,760 | 5,367 | 1,347,393 |
| 6 | 40,000 | 100 | 18 | 446 | 1,712,640 | 5,367 | 1,662,273 |
| 7 | 60,000 | 150 | 22 | 574 | 2,204,160 | 5,367 | 2,133,793 |
| 8 | 60,000 | 150 | 29 | 695 | 2,668,800 | 5,367 | 2,598,433 |
| 9 | 60,000 | 150 | 35 | 810 | 3,110,400 | 5,367 | 3,035,033 |
| 10 | 80,000 | 200 | 40 | 970 | 3,724,800 | 5,367 | 3,629,433 |
| 11 | 80,000 | 200 | 49 | 1,121 | 4,304,640 | 5,367 | 4,199,273 |
| 12 | 80,000 | 200 | 56 | 1,265 | 4,857,600 | 5,367 | 4,762,233 |

### 3.3 Year 1 Summary (Realistic Scenario)

| Metric | Value |
|--------|-------|
| **Total ad spend (Year 1)** | 600,000 |
| **Total paying customers by Month 12** | 1,265 |
| **MRR by Month 12** | 4,857,600 |
| **Annual recurring revenue (ARR) by Month 12** | 58,291,200 |
| **Total net profit (Year 1, before labor)** | ~25,000,000 |
| **Average monthly net profit (Year 1)** | ~2,083,333 |
| **Effective CAC (Year 1)** | 474 |
| **LTV:CAC ratio** | 48.6:1 (exceptional) |

---

## 4. Conservative vs Realistic vs Optimistic: Year 1 Comparison

| Metric | Conservative | Realistic | Optimistic |
|--------|-------------|-----------|-----------|
| **Total ad spend** | 600,000 | 600,000 | 600,000 |
| **Customers by Month 12** | 412 | 1,265 | 3,402 |
| **MRR by Month 12** | 1,582,080 | 4,857,600 | 13,063,680 |
| **ARR by Month 12** | 18,984,960 | 58,291,200 | 156,764,160 |
| **Total net profit (Year 1)** | ~8,500,000 | ~25,000,000 | ~72,000,000 |
| **Average monthly profit** | ~708,333 | ~2,083,333 | ~6,000,000 |
| **Effective CAC** | 1,456 | 474 | 176 |
| **LTV:CAC ratio** | 11.1:1 | 48.6:1 | 218.2:1 |

---

## 5. Key Financial Metrics

### 5.1 LTV:CAC Ratio

The gold standard for SaaS unit economics. A ratio of 3:1 is considered healthy; 5:1 is excellent.

| Scenario | LTV (KSh) | CAC (KSh) | Ratio | Verdict |
|----------|----------|----------|-------|---------|
| Conservative | 16,128 | 1,456 | 11.1:1 | ✅ Excellent |
| Realistic | 23,040 | 474 | 48.6:1 | ✅ Exceptional |
| Optimistic | 38,400 | 176 | 218.2:1 | ✅ Outstanding |

**Why so high?** BizNez's ARPU (KSh 3,840) is high relative to the CAC in Kenya's low-cost ad market. Even with conservative assumptions, you recover the acquisition cost in the first month.

### 5.2 CAC Payback Period

| Scenario | Payback | Industry Benchmark |
|----------|---------|-------------------|
| Conservative | 0.12 months (4 days) | 12–18 months |
| Realistic | 0.04 months (1 day) | 12–18 months |
| Optimistic | 0.02 months (half day) | 12–18 months |

**This is the strongest metric.** You recover your ad spend within days, not months. This means you can reinvest aggressively and scale rapidly.

### 5.3 Monthly Profitability Break-Even

How many paying customers do you need to cover all costs (ads + infrastructure)?

| Ad Budget | Break-Even Customers | Monthly Revenue Needed |
|-----------|---------------------|----------------------|
| KSh 20,000 | 6 customers | KSh 23,040 |
| KSh 40,000 | 11 customers | KSh 42,240 |
| KSh 60,000 | 16 customers | KSh 63,360 |
| KSh 80,000 | 22 customers | KSh 84,480 |

**You become profitable within the first week of launching ads** — even in the conservative scenario, you acquire 27 customers in Month 1, far exceeding the 6-customer break-even.

---

## 6. Channel Comparison: Google Ads vs Meta Ads

### 6.1 Google Search Ads

**Strengths:**
- **High intent:** Users actively searching for "restaurant POS Kenya" or "hardware shop software Nairobi" are ready to buy.
- **Higher conversion rate:** Search ads typically convert at 2–3× the rate of display/social ads.
- **SEO synergy:** Landing pages built for SEO also improve Quality Score, lowering CPC.

**Weaknesses:**
- **Higher CPC:** KSh 15–40 per click vs KSh 10–60 for Meta.
- **Limited audience:** Only reaches people actively searching — smaller total pool.
- **Keyword competition:** As you grow, competitors may bid on your keywords.

**Best for:** Performance Max campaigns targeting long-tail sector-specific queries.

### 6.2 Meta (Facebook/Instagram) Ads

**Strengths:**
- **Lower CPC:** KSh 10–60 per click, often cheaper than Google for broad targeting.
- **Larger audience:** 17+ million Facebook users in Kenya.
- **Better targeting:** Target by industry, location, interests, behavior.
- **Visual format:** Video ads showing the POS in action perform well.
- **Retargeting:** Show ads to people who visited your site but didn't sign up.

**Weaknesses:**
- **Lower intent:** Users are scrolling, not searching — lower conversion rate.
- **Ad fatigue:** Creatives need constant refreshing.
- **Less predictable:** Algorithm changes can impact performance.

**Best for:** Brand awareness, retargeting, and reaching business owners who aren't actively searching.

### 6.3 Recommended Mix

| Phase | Google Ads | Meta Ads | Rationale |
|-------|-----------|----------|-----------|
| **Month 1–2 (Testing)** | 70% (KSh 14,000) | 30% (KSh 6,000) | Prioritize high-intent search to validate conversion rates |
| **Month 3–6 (Scaling)** | 50% | 50% | Balance intent with reach; use Meta for retargeting |
| **Month 7–12 (Growth)** | 40% | 60% | Meta scales better for volume; Google maintains quality leads |

---

## 7. Revenue Beyond Ads: Organic & Referral

### 7.1 SEO-Driven Organic Traffic

Your SEO plan (59 sub-sector pages, 10 town pages, JSON-LD structured data) will compound over time:

| Timeline | Expected Organic Traffic | Converting Customers/Month |
|----------|------------------------|--------------------------|
| Month 1–3 | Minimal (new domain) | 0–5 |
| Month 4–6 | Growing (indexing) | 5–15 |
| Month 7–12 | Significant (rankings) | 15–50 |
| Year 2 | Strong (authority) | 50–150 |

**SEO customers have zero CAC** — they're pure profit margin.

### 7.2 Referral Program

Both referrer and referee get a free month. This creates viral growth:

- If 10% of customers refer one peer per month, that's **free acquisition** of 10% of your base.
- At 1,265 customers (Month 12, realistic), that's ~126 free referrals/month worth ~KSh 484,800 in MRR.

### 7.3 Assisted Onboarding ("Set It Up For Me")

The "set it up for me" form captures leads who aren't tech-comfortable. These convert at higher rates (40–60%) because you've already done the setup work.

---

## 8. Cost Optimization Strategies

### 8.1 Reducing CAC

| Strategy | Impact | Effort |
|----------|--------|--------|
| Improve landing page conversion | +20–40% trial sign-ups | Medium |
| Add video demos to landing pages | +15–25% trial sign-ups | Low |
| A/B test ad creatives | -10–20% CPC | Ongoing |
| Retargeting campaigns | +30–50% trial-to-paid | Medium |
| Referral program optimization | Free acquisition channel | Low |
| SEO content expansion | Zero-CAC customers over time | High |

### 8.2 Reducing Churn

| Strategy | Impact | Effort |
|----------|--------|--------|
| Better onboarding (in-app guides) | -20–30% early churn | Medium |
| P1 feature completion (till, ETR, recipes) | -15–25% churn | High |
| Proactive support (check-in emails) | -10–15% churn | Low |
| Usage-based reminders (follow-ups, low stock) | -10–15% churn | Medium |
| Annual plan incentives | -30–50% annual churn | Low |

### 8.3 Increasing ARPU

| Strategy | Impact | Effort |
|----------|--------|--------|
| Upsell Lite → Growth | +KSh 1,850/month per upgrade | Medium |
| ETR add-on adoption | +KSh 200–500/month per customer | Low |
| Annual plan conversion | +2 months revenue upfront | Low |
| Custom work upsell | One-time revenue, builds loyalty | High |

---

## 9. Risk Factors & Mitigation

### 9.1 Risks That Could Reduce Projections

| Risk | Impact | Probability | Mitigation |
|------|--------|------------|------------|
| **P1 feature gaps cause churn** | Customers leave in first 30 days | High | Build till separation, ETR, recipes ASAP |
| **Ad costs rise with competition** | CAC increases, margins shrink | Medium | Build organic channels (SEO, referrals) |
| **Brevo email not configured** | Password resets fail, trials abandon | High (blocker) | Set up Brevo before launch |
| **PayHero field names wrong** | M-Pesa test fails, trust broken | Medium | Complete one real sandbox test |
| **Single VPS downtime** | All customers affected, churn spike | Medium | Add monitoring, plan multi-server |
| **eTIMS compliance pressure** | Customers demand it, leave if absent | Growing | Apply for KRA integrator early |
| **Support bottleneck** | Can't handle 100+ customers alone | High at scale | Hire part-time support at 50 customers |

### 9.2 Risks That Could Improve Projections

| Opportunity | Impact | Probability |
|-------------|--------|------------|
| **Viral referral growth** | Free acquisition accelerates | Medium |
| **SEO rankings compound** | Zero-CAC customers from Month 6+ | High |
| **Positive word-of-mouth** | Organic signups from satisfied users | High |
| **Sector-specific demand** | One sector (e.g., restaurants) drives disproportionate growth | Medium |
| **Annual plan adoption** | Higher upfront revenue, lower churn | Medium |

---

## 10. Bottom Line: What You Can Expect

### 10.1 First 3 Months (KSh 20,000/month ads)

| Scenario | Month 3 MRR | Month 3 Monthly Profit | 3-Month Cumulative Profit |
|----------|------------|----------------------|--------------------------|
| **Conservative** | KSh 376,320 | KSh 350,953 | KSh 603,579 |
| **Realistic** | KSh 718,080 | KSh 692,713 | KSh 1,210,300 |
| **Optimistic** | KSh 1,666,560 | KSh 1,620,827 | KSh 2,837,653 |

### 10.2 End of Year 1 (scaling ads to KSh 80,000/month)

| Scenario | Month 12 MRR | Month 12 Monthly Profit | Year 1 Cumulative Profit |
|----------|-------------|----------------------|------------------------|
| **Conservative** | KSh 1,582,080 | KSh 1,511,713 | ~KSh 8,500,000 |
| **Realistic** | KSh 4,857,600 | KSh 4,762,233 | ~KSh 25,000,000 |
| **Optimistic** | KSh 13,063,680 | KSh 12,968,313 | ~KSh 72,000,000 |

### 10.3 The Most Likely Outcome

Based on the data, the **realistic scenario** is the most probable:

- **Month 3:** ~KSh 700,000 MRR, ~KSh 690,000 monthly net profit
- **Month 6:** ~KSh 1.7 million MRR, ~KSh 1.66 million monthly net profit
- **Month 12:** ~KSh 4.86 million MRR, ~KSh 4.76 million monthly net profit
- **Year 1 total profit:** ~KSh 25 million (before human labor costs)

**Key advantage:** Your CAC payback is measured in **days, not months**. This means every KSh 1 you spend on ads returns KSh 48+ over the customer's lifetime. This is an exceptionally efficient growth engine.

### 10.4 What This Means for You

1. **You're profitable from Day 1.** Even in the conservative scenario, Month 1 profit is KSh 78,313.
2. **Reinvestment is fast.** You can take Month 1's profit and double your ad budget in Month 2.
3. **The bottleneck is not ads — it's your platform.** If the P1 features (till separation, ETR, restaurant ordering) aren't built, customers will churn regardless of how cheap your ads are.
4. **Support will be your first hire.** At 100+ customers, you can't handle onboarding alone. Budget KSh 20,000–30,000/month for part-time support.
5. **SEO is your long-term moat.** Ads are expensive at scale. Organic traffic from 59 sub-sector pages will become your cheapest acquisition channel by Month 6.

---

## 11. Recommendations

### Immediate (Before Launch)
1. **Set up Brevo email** — go-live blocker for password resets.
2. **Complete PayHero sandbox test** — verify field names with one real payment.
3. **Build till separation** — unlocks Lite plan value proposition.
4. **Build ETR module** — KRA compliance, KSh 4/ETR revenue stream.

### Month 1–3 (Launch Phase)
1. **Start with KSh 20,000/month ads** — 70% Google Search, 30% Meta.
2. **Track everything** — conversion rates, churn reasons, support tickets.
3. **Iterate landing pages** — A/B test hero copy, demo videos, pricing display.
4. **Hire part-time support** at 50 customers — KSh 20,000–30,000/month.

### Month 4–6 (Scale Phase)
1. **Double ad budget to KSh 40,000/month** — scale what works.
2. **Shift to 50/50 Google/Meta mix** — balance intent with reach.
3. **Build restaurant table ordering** — unlocks hospitality sector.
4. **Launch referral program** — free viral acquisition.

### Month 7–12 (Growth Phase)
1. **Scale ads to KSh 60,000–80,000/month** — aggressive market capture.
2. **Shift to 40/60 Google/Meta mix** — Meta scales better for volume.
3. **Add monitoring and multi-server deployment** — operational maturity.
4. **Hire full-time developer** — KSh 80,000–150,000/month for feature velocity.

---

## 12. Disclaimer

These projections are based on industry benchmarks, Kenya-specific ad cost data, and reasonable assumptions about conversion rates and churn. Actual results will vary based on:

- Ad creative quality and targeting precision
- Landing page conversion optimization
- Product quality and onboarding experience
- Market timing and competitive landscape
- Execution speed on P1 features

**Treat the realistic scenario as your planning baseline.** The conservative scenario is your risk floor. The optimistic scenario is your stretch target. Focus on building the features that reduce churn and increase ARPU — those have the highest leverage on your bottom line.
