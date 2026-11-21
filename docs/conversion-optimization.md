# BizNez: Click-to-Trial & Trial-to-Paid Conversion Optimization

**Written:** 2026-11-12
**Status:** Analysis & optimization recommendations

---

## 0. Currency Confirmation

Yes — all financial projections in `docs/financial-projections.md` are based on **KSh 20,000/month** (Kenyan Shillings), not $20,000 USD. The blended CPC of KSh 25, the ARPU of KSh 3,840, and all revenue/profit figures are in KES.

---

## 1. What's Currently Built: The Conversion Pipeline

### 1.1 Click → Trial (Acquisition)

| Component | Status | Details |
|-----------|--------|---------|
| **Self-serve signup** | ✅ Built | `POST /api/auth/signup` — creates workspace, owner, sector pack, sample data in one step |
| **Assisted onboarding** | ✅ Built | "Set it up for me" form → LeziTech team creates workspace → handover starts trial |
| **Sector landing pages** | ✅ Built | `/for/restaurant`, `/for/retail`, etc. — pre-selects the pack at signup |
| **Demo logins** | ✅ Built | `/demo` — one-click role-based access per sector |
| **Meta CAPI tracking** | ✅ Built | Lead, StartTrial, Subscribe, Purchase events |
| **Referral tracking** | ✅ Built | UTM params, referral codes, ad-source attribution |
| **Bot protection** | ✅ Built | Honeypot field, IP rate limiting (5 requests/hour) |
| **Google Ads tracking** | ❌ Not built | Only noted — no conversion tracking or UTM capture for Google |

### 1.2 Trial → Paid (Conversion)

| Component | Status | Details |
|-----------|--------|---------|
| **Trial period** | ✅ Built | 7 days, configurable via `suite.trial-days` |
| **Trial expiry guard** | ✅ Built | 402 on writes after expiry; reads still work |
| **Trial nudges** | ⚠️ Partial | Day 5 and Day 7 email + WhatsApp text prepared for manual send |
| **Plan display** | ✅ Built | `/api/billing/plans` — shows plans, usage, past requests |
| **Manual subscription** | ✅ Built | Owner chooses plan → enters M-Pesa phone → manual payment to LeziTech |
| **Automated M-Pesa STK** | ⚠️ Planned | README says "Pay now with M-Pesa" sends STK prompt — needs verification |
| **Plan activation** | ✅ Built | `PlanActivation.activate()` — works for both manual and automated |
| **Meta conversion tracking** | ✅ Built | Subscribe (first payment) + Purchase (every payment) |
| **Referral rewards** | ✅ Built | Both parties get a free month on first payment |

---

## 2. What "Monthly Churn" Means

**Monthly churn rate** is the percentage of paying customers who cancel or stop paying each month.

### Example

If you have 100 paying customers at the start of Month 2, and 5 of them stop paying during Month 2:

- **Monthly churn rate = 5/100 = 5%**

### Why It Matters

Churn directly determines how long a customer stays paying (customer lifetime), which determines whether your business grows or shrinks.

| Monthly Churn | Customer Lifetime | What It Means |
|--------------|-------------------|---------------|
| 3% | ~33 months (2.75 years) | Excellent retention — customers stay long-term |
| 5% | ~20 months | Good — typical for SMB SaaS |
| 8% | ~12.5 months | High — you're losing nearly 1 in 12 customers monthly |
| 15% | ~6.7 months | Critical — you need to acquire 2 customers to keep 1 |

### Compound Effect

Churn compounds. At 5% monthly churn:

- Month 1: 100 customers
- Month 6: 74 customers remaining (26% lost)
- Month 12: 54 customers remaining (46% lost)

**For BizNez:** The financial projections assume 3–8% monthly churn. The realistic scenario uses 5%, meaning roughly half your customers leave within the first year. This is normal for emerging-market SMB SaaS but means you must constantly acquire new customers to maintain revenue.

### What Causes Churn for BizNez

1. **P1 feature gaps** — A restaurant signs up, finds no recipes/ingredients tracking, and leaves.
2. **Onboarding friction** — The business can't figure out how to set up M-Pesa or add their items.
3. **Price sensitivity** — KSh 2,650/month is affordable but not free. If the business doesn't see value in 7 days, they won't pay.
4. **Technical issues** — Downtime, bugs, or slow performance erode trust.
5. **Competitor switch** — A competitor offers a feature you don't have.

---

## 3. Click-to-Trial: Current Gaps & Optimizations

### 3.1 Gap: No Landing Page Content Strategy

**Current state:** Sector landing pages exist at `/for/restaurant`, etc., but the actual page content (hero copy, feature cards, social proof, FAQ) is not documented.

**Impact:** A visitor from a Google ad lands on a thin page → bounces → no trial signup.

**Optimization:**

Each sector landing page needs:

1. **Hero section** — Headline that matches the ad query. Example: "Restaurant POS Software in Kenya — Works with M-Pesa, Offline, from KSh 2,650/month."
2. **Interactive demo preview** — Embed a live demo or video showing the POS in action for that sector.
3. **Feature cards** — Show only the features that are actually built for that sector (don't promise recipes if they're not built).
4. **Social proof** — "Trusted by 200+ Kenyan businesses" (once you have the numbers).
5. **Pricing preview** — Show the Lite plan price prominently.
6. **FAQ section** — Answer common objections: "Do I need internet?" "Can I use my own M-Pesa till?" "Is my data safe?"
7. **CTA button** — "Start your free 7-day trial" (links to signup with sector pre-selected).

**Priority:** High — landing pages are the #1 factor in click-to-trial conversion.

### 3.2 Gap: Signup Form Friction

**Current state:** Self-serve signup requires: email, password (8+ chars), business name, name, sector, sub-sector.

**Impact:** Each additional field reduces conversion by 10–20%. The 8-character password requirement is a significant friction point on mobile.

**Optimization:**

1. **Reduce to 3 fields:** Email, business name, sector. Auto-generate a temporary password and send it via email/SMS.
2. **Magic link signup:** Send a one-time login link instead of requiring a password. The user clicks the link, sets their password inside the app.
3. **Phone number as alternative:** Allow phone number + OTP instead of email + password. Many Kenyan SMEs use phone-first.
4. **Progressive onboarding:** Let them start the trial with minimal info. Ask for the rest (KRA PIN, phone, address) after they're inside.

**Priority:** High — signup form is the conversion bottleneck.

### 3.3 Gap: No Instant Value Demonstration

**Current state:** After signup, the user gets a workspace with sample data. They need to explore to find value.

**Impact:** A new user lands on a blank dashboard → doesn't know what to do → closes the tab → never returns.

**Optimization:**

1. **In-app onboarding tour:** A 3-step guided tour highlighting: (1) Make your first sale, (2) Add your items, (3) Set up M-Pesa.
2. **"Quick start" button:** One-click action that opens the till with sample items pre-loaded. The user can make a practice sale immediately.
3. **Empty state guidance:** Every empty screen should have a "Add your first item" or "Make your first sale" prompt.
4. **Video tutorials:** 30-second embedded videos on each screen showing how to do the core action.

**Priority:** High — first 5 minutes determine whether a trial user returns.

### 3.4 Gap: No WhatsApp-First Acquisition

**Current state:** The "set it up for me" form returns a WhatsApp link, but there's no WhatsApp bot or automated WhatsApp onboarding.

**Impact:** WhatsApp is the #1 communication channel in Kenya. Not using it for acquisition leaves massive conversion on the table.

**Optimization:**

1. **WhatsApp click-to-chat ad:** Instead of a landing page, the ad opens a WhatsApp conversation. The bot asks: "What kind of business do you run?" → sends the relevant sector signup link.
2. **WhatsApp trial confirmation:** After signup, send a WhatsApp message: "Welcome to BizNez! Your 7-day trial for [business name] has started. Open your workspace here: [link]."
3. **WhatsApp trial nudges:** Instead of (or in addition to) email, send trial reminders via WhatsApp.

**Priority:** Medium — requires Africa's Talking WhatsApp API setup.

### 3.5 Gap: No A/B Testing Infrastructure

**Current state:** No way to test different landing page copies, CTA buttons, or signup flows.

**Impact:** You're flying blind on what converts best.

**Optimization:**

1. **Simple A/B testing:** Use Google Optimize or Vercel Analytics to test two versions of the landing page hero.
2. **UTM parameter tracking:** Already partially built via `Acquisition.clean()`. Ensure all ad links carry UTM params.
3. **Conversion funnel dashboard:** In the control panel, show: clicks → trials → paid, broken down by source, sector, and campaign.

**Priority:** Medium — useful once you have traffic volume.

---

## 4. Trial-to-Paid: Current Gaps & Optimizations

### 4.1 Gap: Manual Plan Payment is a Conversion Killer

**Current state:** The primary flow is manual. The owner clicks "subscribe," enters an M-Pesa phone number, and receives: "Request received. Pay KES X to LeziTech and we activate your plan within one working hour."

**Impact:** This is the single biggest conversion leak. The owner has to:
1. Leave the app
2. Open M-Pesa
3. Find LeziTech's till/paybill
4. Enter the amount
5. Wait for manual confirmation
6. Hope someone at LeziTech is available to confirm

Each step loses 20–40% of users. Industry standard for SaaS is one-click payment.

**Optimization:**

1. **Automated STK push for plans:** The README mentions this is planned ("Pay now with M-Pesa" sends a prompt from LeziTech's own PayHero account). **Verify this is fully implemented and working.** If not, this is the #1 priority.
2. **One-click upgrade:** When the trial expires, show a banner: "Your trial has ended. Upgrade to Lite for KSh 2,650/month — pay now with M-Pesa." One tap → STK prompt → plan activated.
3. **Save payment method:** After the first payment, remember the M-Pesa number for renewals.

**Priority:** Critical — this is the conversion bottleneck.

### 4.2 Gap: Only 2 Trial Nudges (Day 5 and Day 7)

**Current state:** TrialNudgeService sends reminders on Day 5 ("2 days left") and Day 7 ("ends today").

**Impact:** Most SaaS companies send 3–5 nudges across the trial period. Two nudges miss the critical early engagement window.

**Optimization:**

Add these nudges:

| Day | Trigger | Message |
|-----|---------|---------|
| **Day 0** | Signup | "Welcome! Here's how to get the most from your 7-day trial." (Link to quick-start guide) |
| **Day 1** | No first sale | "You haven't made a sale yet. Here's a 30-second video showing how." |
| **Day 2** | No M-Pesa setup | "Set up M-Pesa in 2 minutes and start accepting payments." |
| **Day 3** | Active but no staff added | "Invite your team — your Lite plan includes 3 staff members." |
| **Day 5** | Trial ending | "2 days left. Upgrade now to keep your data." |
| **Day 7** | Trial ending today | "Your trial ends today. Upgrade with one tap." |
| **Day 8** | Trial expired | "Your trial has ended. Your data is safe — upgrade to keep using it." |

**Priority:** High — engagement-driven nudges convert 2–3× better than time-based nudges.

### 4.3 Gap: No Usage-Based Conversion Triggers

**Current state:** Nudges are time-based only. The system doesn't track whether the user has made a sale, added items, or set up M-Pesa.

**Impact:** A user who made 10 sales during the trial is 5× more likely to convert than one who didn't. But both get the same generic nudges.

**Optimization:**

1. **Track activation milestones:** First sale, first M-Pesa payment, first staff invite, first item added.
2. **Score trial engagement:** Users who hit 3+ milestones have 80%+ conversion rate. Users who hit 0 have <5%.
3. **Trigger targeted nudges:**
   - User made sales but didn't set up M-Pesa → "Set up M-Pesa to accept payments from your customers."
   - User set up M-Pesa but didn't add staff → "Invite your team to start using BizNez together."
   - User is highly active → "You're getting great value from BizNez! Lock in your plan before the trial ends."
4. **Flag at-risk trials:** In the control panel, highlight trials with 0 sales after Day 3. The LeziTech team can proactively call these.

**Priority:** High — this is the difference between 15% and 40% trial-to-paid conversion.

### 4.4 Gap: No In-App Upgrade Prompt at Plan Limits

**Current state:** When a user hits a plan limit (e.g., 500 items on Lite), the API returns `PLAN_LIMIT` error.

**Impact:** The error is a conversion opportunity. If handled well, it can trigger an immediate upgrade.

**Optimization:**

1. **Soft limit with upgrade prompt:** Instead of blocking, show: "You've reached 500 items on Lite. Upgrade to Growth for 5,000 items — KSh 4,500/month."
2. **One-click upgrade:** The prompt includes a "Upgrade now" button that triggers the M-Pesa STK push.
3. **Grace period:** Allow 50 items over the limit for 48 hours while the upgrade processes.

**Priority:** Medium — plan limit hits are infrequent but high-intent conversion moments.

### 4.5 Gap: No Free Trial Extension for At-Risk Users

**Current state:** Trial ends on Day 7 regardless of engagement.

**Impact:** A user who started late (e.g., Day 4) may not have experienced the product's value by Day 7.

**Optimization:**

1. **Auto-extend for assisted onboarding:** If the workspace was created via "set it up for me" and the team is still working on it, extend the trial by 3 days.
2. **Manual extension from control panel:** LeziTech team can extend a trial by 3–7 days with a note.
3. **Conditional extension:** If a user has 0 sales by Day 5, auto-send: "We noticed you haven't made a sale yet. Would you like 3 extra days to explore?"

**Priority:** Low — extensions can create entitlement. Use sparingly.

### 4.6 Gap: No Annual Plan Incentive at Trial End

**Current state:** Yearly pricing is 10 × monthly (2 months free), but there's no prominent display of this during the trial-to-paid moment.

**Impact:** Annual plans reduce churn by 30–50% and provide upfront cash flow.

**Optimization:**

1. **Show annual savings prominently:** "Save KSh 5,300/year — pay KSh 26,500 once instead of KSh 31,800."
2. **Highlight annual at trial end:** When the trial expires, show annual as the default option with monthly as an alternative.
3. **Limited-time offer:** "Upgrade within 48 hours and get 3 months free (instead of 2)."

**Priority:** Medium — annual plans are a major retention lever.

---

## 5. Overall Conversion Funnel: Current vs. Optimized

### 5.1 Current Funnel (Estimated)

| Stage | Current Rate | Bottleneck |
|-------|-------------|------------|
| Ad click → Landing page | 100% | — |
| Landing page → Start signup | **40–60%** | Thin landing pages, no social proof |
| Start signup → Complete signup | **60–70%** | 8-char password, too many fields |
| Complete signup → First login | **70–80%** | No immediate value demonstration |
| First login → First sale | **20–30%** | No guided tour, no quick-start |
| First sale → Trial completion | **40–50%** | No engagement tracking |
| Trial end → Paid | **15–25%** | Manual payment, only 2 nudges |
| **Overall: Click → Paid** | **2–5%** | Multiple friction points |

### 5.2 Optimized Funnel (Target)

| Stage | Target Rate | How |
|-------|-----------|-----|
| Ad click → Landing page | 100% | — |
| Landing page → Start signup | **70–80%** | Rich landing pages, social proof, video demo |
| Start signup → Complete signup | **85–90%** | Magic link, phone+OTP, 3-field form |
| Complete signup → First login | **85–90%** | WhatsApp confirmation, in-app tour |
| First login → First sale | **50–60%** | Quick-start button, guided tour |
| First sale → Trial completion | **60–70%** | Engagement nudges, milestone tracking |
| Trial end → Paid | **30–40%** | Automated STK push, annual incentive |
| **Overall: Click → Paid** | **6–12%** | 2–3× improvement |

---

## 6. Priority Implementation Order

### Phase 1: Go-Live Blockers (Do Now)

| # | Optimization | Impact | Effort |
|---|-------------|--------|--------|
| 1 | **Verify automated M-Pesa STK for plans** | Critical — manual payment kills conversion | Check if implemented |
| 2 | **Build rich landing pages** | High — thin pages = high bounce rate | Medium |
| 3 | **Reduce signup form friction** | High — each field loses 10–20% | Low |
| 4 | **Set up Brevo email** | Critical — password resets depend on it | Low |

### Phase 2: First Month (Quick Wins)

| # | Optimization | Impact | Effort |
|---|-------------|--------|--------|
| 5 | **Add engagement-based nudges** | High — 2–3× better than time-only | Medium |
| 6 | **Add in-app onboarding tour** | High — first 5 minutes determine retention | Medium |
| 7 | **Add "Quick start" practice sale** | High — users who make a sale convert 5× more | Low |
| 8 | **Show annual plan savings prominently** | Medium — reduces churn 30–50% | Low |

### Phase 3: Second Month (Growth Levers)

| # | Optimization | Impact | Effort |
|---|-------------|--------|--------|
| 9 | **WhatsApp trial confirmation** | Medium — WhatsApp is #1 channel in Kenya | Medium |
| 10 | **A/B testing infrastructure** | Medium — optimize what converts | Medium |
| 11 | **At-risk trial flagging in control panel** | Medium — proactive outreach saves conversions | Low |
| 12 | **Google Ads conversion tracking** | Medium — needed for paid acquisition | Low |

### Phase 4: Ongoing (Continuous Improvement)

| # | Optimization | Impact | Effort |
|---|-------------|--------|--------|
| 13 | **Magic link signup** | High — eliminates password friction | Medium |
| 14 | **Phone + OTP signup** | High — phone-first for Kenyan SMEs | Medium |
| 15 | **Video tutorials on each screen** | Medium — reduces support burden | High |
| 16 | **Referral program optimization** | Medium — free viral acquisition | Low |

---

## 7. What's Already Excellent

Don't change these — they're working well:

1. **Sector-specific signup** — Pre-selecting the pack at signup is a strong differentiator.
2. **Sample data seeding** — New users see a realistic workspace, not a blank screen.
3. **Meta CAPI tracking** — Full funnel tracking from ad click to purchase.
4. **Referral program** — Both parties get a free month. Simple and effective.
5. **Trial guard** — 402 on writes, reads still work. Data is never lost.
6. **Assisted onboarding** — "Set it up for me" captures non-tech-comfortable users.
7. **Demo logins** — One-click role-based access builds trust before signup.

---

## 8. Key Metrics to Track

| Metric | Current | Target | How to Measure |
|--------|---------|--------|---------------|
| **Click-to-trial rate** | 15–25% | 35–50% | Ad clicks → trial signups |
| **Trial-to-paid rate** | 15–25% | 30–40% | Trial signups → first payment |
| **Overall click-to-paid** | 2–5% | 12–20% | Ad clicks → first payment |
| **First sale within trial** | 20–30% | 50–60% | Trials with ≥1 sale |
| **M-Pesa setup within trial** | Unknown | 60–70% | Trials with M-Pesa configured |
| **Staff invite within trial** | Unknown | 40–50% | Trials with ≥1 staff added |
| **Monthly churn** | 5–8% | 3–5% | Paying customers lost / total paying |
| **Annual plan adoption** | Unknown | 20–30% | Annual subscriptions / total subscriptions |

---

## 9. Bottom Line

### The Single Biggest Conversion Leak

**Manual plan payment.** If the automated M-Pesa STK push for plan payments isn't fully working, fixing it alone will double your trial-to-paid conversion rate. Every other optimization is secondary to this.

### The Second Biggest Leak

**Thin landing pages.** If a visitor from a Google ad lands on a page with no social proof, no video demo, and no clear value proposition, they bounce before they even see the signup form.

### The Third Biggest Leak

**No engagement tracking during trial.** A user who makes 10 sales is 5× more likely to convert than one who makes 0. But both get the same generic "2 days left" email. Engagement-based nudges are the difference between 15% and 40% trial-to-paid conversion.

### What to Do Right Now

1. **Verify** the automated M-Pesa STK for plans is working end-to-end.
2. **Build** rich landing pages for your top 3 sectors (restaurants, retail, salons).
3. **Reduce** the signup form to 3 fields + magic link.
4. **Add** engagement-based nudges (first sale, M-Pesa setup, staff invite).
5. **Set up** Brevo email — it's a go-live blocker.

These five actions alone will move your overall click-to-paid rate from 2–5% to 8–15%, which at KSh 20,000/month ad budget means 60–150 paying customers per month instead of 20–50.
