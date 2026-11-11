# BizNez: Platform Enhancements & Go-to-Market Strategy

Written 2026-11-11. Status: **plan — for discussion and refinement**.

This document captures new platform enhancements, the revised pricing structure, the M-Pesa onboarding flow, and the landing page strategy. It builds on the existing SEO-AEO-GEO-PLAN, sector study, and client guide.

---

## 1. Revised Pricing Plans

### 1.1 The Problem

The original Lite plan (KSh 2,000, 2 staff) is too restrictive for how Kenyan SMEs actually operate. Most businesses start with multiple tellers at the counter — 3 or more people handling sales — and they need each teller to have their own cash drawer with end-of-day reconciliation. Without this, the Lite plan doesn't solve the basic problem of a multi-teller shop.

Additionally, KSh 2,000/month is unsustainable given infrastructure, support, and maintenance costs. The price needs to reflect the value while remaining accessible.

### 1.2 Revised Plans

| Plan | KSh/month | Staff | Drawers | Branches | Items | Yearly |
|------|-----------|-------|---------|----------|-------|--------|
| **Lite** | **2,650** | **3** | **3** | 1 | 500 | 26,500 |
| Growth | 4,500 | 8 | 8 | 3 | 5,000 | 45,000 |
| Pro | 9,000 | 30 | 30 | 10 | Unlimited | 90,000 |

**Changes from original:**
- Lite raised from KSh 2,000 to KSh 2,650 (32.5% increase).
- Lite staff increased from 2 to 3.
- **Each staff member gets their own cash drawer** with individual open/close/reconciliation.
- Yearly pricing: 10 × monthly (effective 2 months free).

**Rationale for KSh 2,650:**
- Still below most competitors (KSh 3,000–8,000/month).
- Covers infrastructure cost per tenant on the Contabo VPS.
- Leaves room for support costs.
- The 3-staff limit matches how a small SME starts: owner + 2 tellers, or 3 tellers where one doubles as the evening reconciler.

### 1.3 Drawer per Staff — Included in Lite

Every staff member who logs in has their own cash drawer. This is not an add-on; it's part of the base experience. The drawer tracks:

- **Opening cash** (typed when the drawer opens at start of shift).
- **All sales** attributed to that staff member, broken down by payment method (cash, M-Pesa STK, card, pay-later).
- **Refunds and voids** processed by that staff member.
- **Expected cash** at close of day = opening cash + cash sales – cash refunds – cash voids.

This is the foundation for end-of-day reconciliation (section 4).

---

## 2. Staff Till Separation & End-of-Day Reconciliation

### 2.1 The Two Models

A business can operate its till in one of two modes, chosen in **Settings → Till & Drawers**:

#### Model A: Staff Drawers + Owner Reconciliation (Lite-friendly)

**How it works:**
1. Each staff member opens their drawer in the morning, recording the opening cash float.
2. Throughout the day, every sale is attributed to the staff member who took it.
3. In the evening, each staff member:
   - Counts their physical cash.
   - Enters the counted amount in their drawer's "Close" screen.
   - **They do NOT see what the system expects** — they only report what they have.
4. The owner (or manager) reviews the reconciliation summary, which shows:
   - Per staff: expected cash vs. reported cash.
   - The discrepancy (shortage or surplus).
   - Breakdown by payment method: how much the system expects from till (cash), STK (M-Pesa), and card.
5. The owner records the reconciliation result: **matched**, **discrepancy noted**, or **investigated**.
6. Reconciliation can be done:
   - Same evening before closing.
   - Next morning.
   - End of week (for businesses that batch-reconcile).

**Why staff don't see the expected amount:**
This prevents a dishonest staff member from adjusting their cash count to match the system. The owner sees the truth.

**What the discrepancy analysis reveals:**
- **Cash discrepancy:** The staff member may have made an arithmetic error, misplaced cash, failed to confirm an M-Pesa message (took cash instead), or took money.
- **STK discrepancy:** The staff member may have sent an STK prompt but not recorded the sale, or recorded a sale without sending the prompt.
- **Card discrepancy:** Similar to STK — a card sale was made but not recorded, or vice versa.

The system shows the expected totals per payment method so the owner can pinpoint where the gap is.

#### Model B: Dedicated Accountant Role (Growth/Pro)

**How it works:**
- A staff member is assigned the **Accountant** role.
- The Accountant can:
  - Open and close all drawers.
  - View the reconciliation summary (expected vs. reported for all staff).
  - Record discrepancies and notes.
  - Run financial reports (when P&L module is built).
- The owner retains full access and can override the Accountant.

**When to use:** Businesses with 4+ staff where the owner doesn't want to handle daily reconciliation personally.

### 2.2 Reconciliation Screen (Owner/Accountant View)

The reconciliation screen shows:

| Staff | Opening Cash | Cash Sales | Cash Refunds | Expected Cash | Reported Cash | Discrepancy | Status |
|-------|-------------|------------|--------------|---------------|---------------|-------------|--------|
| Jane | 5,000 | 12,300 | 500 | 16,800 | 16,500 | -300 | ⚠ Discrepancy |
| John | 3,000 | 8,700 | 0 | 11,700 | 11,700 | 0 | ✓ Matched |
| Mary | 4,000 | 15,200 | 1,000 | 18,200 | 18,200 | 0 | ✓ Matched |

**Payment method breakdown (per staff, expandable):**

| Staff | Expected Cash | Expected STK | Expected Card | Expected Pay-later |
|-------|--------------|-------------|--------------|-------------------|
| Jane | 16,800 | 23,400 | 5,000 | 2,000 |

If Jane's cash is short by KSh 300 but her STK total matches the M-Pesa statements, the owner knows the issue is in the cash drawer — not in the M-Pesa flow.

### 2.3 Reconciliation Record

Each reconciliation is saved with:
- Date and time.
- Who performed it (owner or accountant).
- Per-staff results.
- Notes field for the owner's comments.
- Whether the drawer was **locked** after reconciliation (prevents further edits to that day's sales by that staff).

### 2.4 Activity Log Integration

All drawer events are logged in the activity log:
- Drawer opened (staff, time, opening cash).
- Drawer closed (staff, time, reported cash).
- Reconciliation performed (who, when, discrepancies).
- Any manual adjustments to expected amounts (with reason).

This creates an audit trail for the accountant or for dispute resolution.

---

## 3. M-Pesa Onboarding & Testing Flow

### 3.1 The Trust Screen

Before a business can use M-Pesa STK prompts, they see a dedicated screen in **Settings → Payments** that makes three things crystal clear:

1. **"We Don't Hold Your Money"** — A prominent banner:
   > *"Money from your customers goes directly to your M-Pesa till or PayHero account. LeziTech never touches your customers' money."*

2. **"Test It Now"** — A button labeled **"Send KSh 10 Test"** that triggers a real STK push.

3. **Method selection** — If no method is configured, the screen guides them to set one up (section 3.3).

### 3.2 The KSh 10 Test Flow

**Prerequisites:** The business has already configured either Daraja or PayHero in Settings → Payments.

**Flow:**
1. Owner clicks **"Send KSh 10 Test"**.
2. An overlay card appears:
   - Title: "Test M-Pesa Prompt"
   - Instruction: "Enter your M-Pesa number to receive a KSh 10 test payment."
   - Input field: Phone number (pre-filled with the owner's number from their profile, editable).
   - Info text: "A real KSh 10 will be sent to your number. It will go directly to your [till/paybill] via [Daraja/PayHero]."
   - Button: "Send Test"
3. On confirm, the system triggers a real STK push for KSh 10.
4. The owner's phone receives the M-Pesa prompt.
5. They enter their M-Pesa PIN and complete the payment.
6. The callback confirms the payment.
7. The overlay updates:
   - ✅ "KSh 10 received! Your M-Pesa integration is working."
   - Transaction details: reference, time, method (Daraja/PayHero).
   - Button: "Start Using M-Pesa" — closes the overlay and enables M-Pesa on the till.

**If the test fails:**
- The overlay shows: "The test didn't go through. Check your M-Pesa PIN and try again, or verify your [Daraja/PayHero] settings."
- A "Troubleshoot" link opens the relevant guide.

**Why KSh 10?**
- It's the minimum meaningful amount — low enough to be a no-brainer test, high enough to confirm the money actually flows.
- The business receives the KSh 10 in their account, so it's not lost — it's a real transaction that proves the system works.

### 3.3 No Method Configured — Setup Guide

If the owner clicks "Send KSh 10 Test" but hasn't configured Daraja or PayHero yet, they see:

**"Set Up M-Pesa First"**

Two cards are displayed:

#### Card 1: PayHero (Recommended — Faster Setup)

> *"Connect your existing till or paybill in minutes. No developer account needed."*

**Steps (in-app wizard):**
1. **Create PayHero account** — Button opens `https://payhero.co.ke/register` in a new tab.
2. **Link your till/paybill** — In-app instructions with screenshots showing where to go in the PayHero dashboard.
3. **Get API keys** — In-app instructions: PayHero → Account → API Keys → copy username and password.
4. **Enter credentials** — Form in the wizard: API username, API password, Channel ID.
5. **Test connection** — System reads the PayHero wallet to verify connectivity.
6. **Done** — Return to the trust screen and run the KSh 10 test.

**External reference:** A link to a detailed guide on `lezitech.site/suite/guides/connect-payhero` is also available for those who prefer reading.

#### Card 2: Daraja (Free — No Middleman)

> *"Use Safaricom's Daraja API directly. No transaction fees to a middleman, but setup takes longer."*

**Steps (in-app wizard + external guide):**
1. **Visit Safaricom Developer Portal** — Button opens `https://developer.safaricom.co.ke` in a new tab.
2. **Register for Daraja** — External guide with screenshots: create account, verify business details, request production access.
3. **Create an app** — External guide: create a new app, note the Consumer Key and Consumer Secret.
4. **Get your till/paybill details** — In-app form: environment (sandbox/production), type (Buy Goods till / Paybill), store number (for till), till/paybill number, Lipa na M-Pesa passkey.
5. **Enter credentials** — Form in the wizard: consumer key, consumer secret, passkey, and the details from step 4.
6. **Test connection** — System signs in to Daraja and (on production) registers the C2B URLs.
7. **Done** — Return to the trust screen and run the KSh 10 test.

**External reference:** A detailed guide on `lezitech.site/suite/guides/connect-daraja` covers the Safaricom shop visit process, document requirements, and timeline.

### 3.4 PayHero Charges Display

Whenever PayHero is selected as the method, a **charges notice** is shown:

> **"PayHero Transaction Fees"**
>
> *PayHero charges a fee on each M-Pesa transaction. The fee is deducted from the amount your customer pays. You receive the remainder.*
>
> | Transaction Amount | PayHero Fee | You Receive (on KSh 1,000) |
> |-------------------|-------------|---------------------------|
> | KSh 1 – 49 | KSh 0 | KSh 1,000 |
> | KSh 50 – 99 | KSh 1 | KSh 999 |
> | KSh 100 – 199 | KSh 2 | KSh 998 |
> | KSh 200 – 299 | KSh 3 | KSh 997 |
> | KSh 300 – 399 | KSh 4 | KSh 996 |
> | KSh 400 – 499 | KSh 5 | KSh 995 |
> | KSh 500 – 699 | KSh 7 | KSh 993 |
> | KSh 700 – 999 | KSh 10 | KSh 990 |
> | KSh 1,000 – 1,499 | KSh 13 | KSh 987 |
> | KSh 1,500 – 1,999 | KSh 16 | KSh 984 |
> | KSh 2,000 – 2,999 | KSh 21 | KSh 979 |
> | KSh 3,000 – 3,999 | KSh 27 | KSh 973 |
> | KSh 4,000 – 4,999 | KSh 32 | KSh 968 |
> | KSh 5,000 – 6,999 | KSh 41 | KSh 959 |
> | KSh 7,000 – 9,999 | KSh 56 | KSh 944 |
> | KSh 10,000 – 14,999 | KSh 75 | KSh 925 |
> | KSh 15,000 – 19,999 | KSh 94 | KSh 906 |
> | KSh 20,000 – 24,999 | KSh 112 | KSh 888 |
> | KSh 25,000 – 34,999 | KSh 140 | KSh 860 |
> | KSh 35,000 – 49,999 | KSh 189 | KSh 811 |
> | KSh 50,000 – 69,999 | KSh 232 | KSh 768 |
> | KSh 70,000 – 99,999 | KSh 294 | KSh 706 |
> | KSh 100,000 – 149,999 | KSh 368 | KSh 632 |
> | KSh 150,000 – 199,999 | KSh 413 | KSh 587 |
> | KSh 200,000 – 249,999 | KSh 455 | KSh 545 |
> | KSh 250,000 – 299,999 | KSh 497 | KSh 503 |
> | KSh 300,000 – 349,999 | KSh 539 | KSh 461 |
> | KSh 350,000 – 399,999 | KSh 581 | KSh 419 |
> | KSh 400,000 – 499,999 | KSh 655 | KSh 345 |
> | KSh 500,000 – 749,999 | KSh 798 | KSh 202 |
> | KSh 750,000 – 999,999 | KSh 945 | KSh 55 |
> | KSh 1,000,000+ | KSh 1,050 | KSh 0 |

*Source: PayHero Kenya published rates. Fees are subject to change — check payhero.co.ke for the latest.*

**Below the table:**
> **"Daraja (Buy Goods Till)"** charges **0.55%** of the transaction amount, capped at **KSh 200** for amounts above KSh 36,363. For amounts KSh 500 and below, Daraja charges **KSh 0** to the merchant.
>
> *Compare: on a KSh 1,000 sale, PayHero charges KSh 13, while Daraja charges KSh 5.50 (0.55%). Daraja is cheaper on most transactions but requires more setup.*

**Implementation note:** The fee table should be stored as data (JSON) so it can be updated when PayHero changes their rates, without a code deployment. A "Last updated" date should be shown.

### 3.5 Fallback: Till Number Without STK

If a business hasn't set up STK prompts yet (or chooses not to), they can still operate:

- The cashier selects **M-Pesa** as the payment method.
- The screen shows the business's till number (or a list of till numbers if multiple).
- The customer manually pays via M-Pesa → Lipa na M-Pesa → Enter the till number.
- The cashier confirms the payment manually (types "confirmed").
- The sale is recorded as M-Pesa.

**This is explicitly supported** — a business doesn't need STK prompts to use BizNez. The STK prompt is a convenience that reduces errors and speeds up the flow, but the till number method works from day one.

**Multiple till numbers:** A business can register several till numbers (e.g., different branches or different owners' tills). The cashier selects which till to use for each sale. This is stored in the `integrations` table and selectable on the till screen.

---

## 4. SEO/GEO/AEO Landing Page Strategy

### 4.1 Building on the Existing Plan

The SEO-AEO-GEO-PLAN.md already defines the page architecture (59 sub-sector pages, 10 town pages, guides, comparisons, tools). This section adds the **landing page structure and content strategy** for each sector, optimized for Google Ads (Search/Performance Max campaigns) as the paid acquisition channel.

### 4.2 Landing Page Structure (Per Sub-Sector)

Each sub-sector landing page at `lezitech.site/suite/sectors/{sector}/{sub}` follows this structure:

#### Hero Section
- **H1:** "[Business Type] POS System in Kenya — Start Free for 7 Days"
  - Example: "Hardware Shop POS System in Kenya — Start Free for 7 Days"
- **Subheading:** One sentence that names the business's top pain point and how BizNez solves it.
  - Example: "Track stock by piece, bag, and metre. Set floor prices so your staff never sells below cost. Accept M-Pesa without holding a phone to the customer's."
- **CTA:** "Start Free Trial" (primary) + "See It In Action" (secondary, scrolls to demo preview).
- **Trust badges:** "M-Pesa integrated" · "Works offline" · "No installation" · "Made in Kenya".

#### Demo Preview
- A static screenshot or short GIF of the dashboard for that sub-sector, built from its sample data.
- Caption: "This is what a [business type] dashboard looks like — pre-loaded with [sample items relevant to the sub-sector]."

#### Features Section (3–4 Cards)
Each card addresses a specific need of that sub-sector:

**Example — Hardware Shop:**
1. **Pack & Loose Tracking** — "Buy cement by the bag, sell by the piece. Buy paint by the tin, sell by the litre. The system converts automatically." *(Note: Pack-and-loose is a P1 gap — this card should say "Coming soon" or be replaced with a currently available feature.)*
1. **Floor Price Control** — "Set the lowest price your staff can sell at. Stop giving away margins on 'friend discounts'."
2. **Customer Credit Accounts** — "Sell on account with a credit limit. Track who owes you and when they last paid." *(Note: Customer statements is a P1 gap — partial credit.)*
3. **M-Pesa & Cash** — "Accept M-Pesa STK prompts, cash, card, or pay-later. Each cashier has their own drawer."

**⚠ Important:** Only feature what's **currently built**. If a feature is a P1 gap, either:
- Replace it with a built feature that matters to that sub-sector.
- Mark it "Coming soon" with a "Tell me when it's ready" form.
- Don't promise it without the qualifier — this damages trust.

#### Pricing Section
- Show all three plans with the Lite plan highlighted.
- For the sub-sector, add a note: "Most [business type] start on Lite and move to Growth when they add [specific trigger, e.g., 'a second branch' or 'wholesale customers']."

#### FAQ Section (3–6 Questions)
Answered in plain sentences, marked up with `FAQPage` JSON-LD. Examples for hardware:
- "How much does a POS system cost for a hardware shop in Kenya?"
- "Can the POS track items I sell by weight and by piece?"
- "Does it work if my internet goes down?"
- "Do I need an M-Pesa till number to use it?"
- "Can my staff have different login pins?"

#### Social Proof (when available)
- Customer quote or case study from that sub-sector.
- "Trusted by [X] [business type] in Kenya" — only when the number is real.

#### Final CTA
- "Start Your Free 7-Day Trial" + "Or let us set it up for you" (links to the assisted onboarding form).

### 4.3 Google Ads Integration

**Note:** Google Ads (Search campaigns and Performance Max) will be used as a paid acquisition channel alongside Meta ads.

**Implementation:**
- UTM parameters from Google Ads (`gclid`, `utm_source`, `utm_medium`, `utm_campaign`) are captured by the existing ad-source tracking system.
- The landing page stores the source, so the control panel can show trials and conversions per Google Ads campaign.
- No Google Ads API integration is needed at this stage — UTM-based attribution is sufficient.
- Google Ads conversion tracking can be added via the Google tag on the marketing site, firing on `start_trial` and `setup_request` events.

**Recommended campaign structure:**
- **Search campaigns** targeted at high-intent queries: "[business type] POS Kenya", "POS system price Kenya", "M-Pesa POS integration".
- **Performance Max** for broader reach, using the sector landing pages as final URLs.
- **Ad copy** should reference: free 7-day trial, M-Pesa integration, offline capability, KSh 2,650 starting price, "made in Kenya".

### 4.4 Content Gaps to Address

The following content pieces should be created alongside the landing pages:

1. **Guide: "How to Connect Your M-Pesa Till to a POS"** — Covers both Daraja and PayHero paths, with screenshots. This serves as both an SEO asset and the external reference for the in-app wizard.
2. **Guide: "How to Stop Staff Stealing at the Till"** — Naturally leads into the drawer separation and reconciliation feature.
3. **Comparison: "M-Pesa Till Payment vs STK Prompt"** — Explains the difference, when to use each, and why STK is better (but not required).
4. **Tool: Turnover Tax Calculator** — Already planned; strong link magnet.
5. **Tool: VAT Calculator** — Already planned.

---

## 5. Identified Gaps & Questions

### 5.1 Gaps Noticed During This Analysis

**Gap 1: PayHero Fee Data — Verification Needed**
The fee table in section 3.4 is based on publicly available PayHero rate information. **This needs to be verified** by:
- Checking the PayHero dashboard or contacting PayHero directly for the current rate card.
- Confirming whether the fees shown are the **merchant fees** (deducted from what the business receives) or the **customer fees** (charged to the customer's M-Pesa).
- Confirming whether PayHero has changed their fee structure recently.

**Gap 2: Pack-and-Loose on Landing Pages**
Several sub-sectors (hardware, wholesale, agrovet, minimart) will have "Pack & Loose" as a top feature on their landing page — but this is a **P1 gap** in the product. Until it's built, these landing pages either need to:
- Promote a different feature as the #1 card.
- Clearly mark "Coming soon" with a waitlist form.
- **Decision needed:** What's the preferred approach?

**Gap 3: Customer Statements on Landing Pages**
Hardware shops, wholesale, and service businesses need customer statements — another **P1 gap**. The landing pages for these sectors will need to handle this similarly to Gap 2.

**Gap 4: Drawer Reconciliation — Reporting**
The reconciliation feature needs a **report** that shows:
- Historical reconciliation results per staff, per day.
- Running discrepancy totals (how much each staff is short/surplus over the week/month).
- Export to CSV for the accountant.
**Question:** Should this be part of the initial reconciliation build, or a P2 addition?

**Gap 5: Drawer Opening Cash — Enforcement**
**Question:** Should the system require staff to enter opening cash before they can make their first sale? Or is it optional (defaulting to KSh 0 if not entered)?

**Recommendation:** Require it. A drawer opened without a recorded float is a reconciliation risk. The system should block sales until opening cash is entered.

**Gap 6: Multi-Shift Drawers**
**Question:** Can a staff member close and reopen their drawer within the same day (e.g., lunch break, shift change)? Or is it one open/close cycle per calendar day?

**Recommendation:** Support multiple open/close cycles per day. A staff member may close at lunch and reopen in the afternoon. The reconciliation aggregates all cycles for that staff on that day.

**Gap 7: KSh 10 Test — Idempotency**
**Question:** Can the owner run the KSh 10 test multiple times? Should there be a cooldown (e.g., one test per hour)?

**Recommendation:** Allow multiple tests but rate-limit to one per 15 minutes. Each test is a real KSh 10 transaction — the business receives the money, but abuse is possible.

**Gap 8: Daraja Setup — Timeline**
**Question:** How long does it typically take to get Daraja production credentials? The in-app wizard should set realistic expectations.

**Research needed:** Safaricom's Daraja production approval timeline. Sandbox is instant; production may take 1–3 business days. The wizard should communicate this.

**Gap 9: PayHero — Account Creation Friction**
**Question:** Does PayHero require KYC/business verification before the API keys work for live transactions? If so, how long does that take?

**Research needed:** PayHero's onboarding timeline for a new business. The wizard should set expectations.

**Gap 10: Reconciliation — Who Can See What**
**Question:** Can a staff member see their own reconciliation result (expected vs. reported) after the owner has reviewed it? Or is that owner-only?

**Recommendation:** Staff can see their own drawer's history (opening cash, sales, reported cash) but **not** the discrepancy column. Only the owner and accountant see the full reconciliation with discrepancies.

### 5.2 Questions for the Owner

1. **Lite plan pricing:** Is KSh 2,650 the final price, or is it a "current promotional price" that will be raised further later? If it's promotional, should the landing pages show KSh 2,000 crossed out with KSh 2,650 as the "current" price, or just show KSh 2,650?

2. **Growth and Pro plans:** Should these also get the drawer separation feature, or is it already implied that all plans have it? (Assumption: all plans include drawer separation per staff.)

3. **KSh 10 test refund:** The KSh 10 goes to the business's own account — it's not lost. Should the system mention this explicitly? ("The KSh 10 goes to your till — you keep it.")

4. **Reconciliation lock:** After the owner reconciles a drawer, should that staff's sales for that day be **locked** (no further edits, voids, or refunds)? Or can the owner still adjust things?

5. **Google Ads budget:** Is there a target daily budget for the Google Ads campaigns? This affects the landing page CTA strategy (aggressive trial sign-up vs. "set it up for me" assisted onboarding).

6. **Daraja vs PayHero recommendation:** Should the platform **nudge** businesses toward one method? (PayHero is faster to set up but costs more per transaction. Daraja is free but slower to set up.)

7. **Multiple till numbers:** A business may have till numbers for different branches or different owners. How many till numbers should be allowed per business on the Lite plan?

8. **Accountant role on Lite:** The Lite plan has 3 staff. If one is an accountant, that leaves 2 tellers. Is this acceptable, or should the Lite plan not include the dedicated Accountant role (Model B) — only the owner reconciliation (Model A)?

---

## 6. Implementation Priorities

Based on the above, here's the recommended build order:

### Phase 1: Before Go-Live (Blocking)
1. **Drawer separation per staff** — Database schema, open/close API, per-staff cash tracking.
2. **Reconciliation screen** — Owner view, expected vs. reported, discrepancy analysis.
3. **M-Pesa trust screen** — "We don't hold your money" banner, KSh 10 test flow.
4. **PayHero charges display** — Fee table, stored as data, with "last updated" date.
5. **Daraja/PayHero setup wizard** — In-app steps + external guide links.
6. **Lite plan revision** — Update plan limits: 3 staff, 3 drawers, KSh 2,650.
7. **Landing page content** — Top 20 sub-sectors with hand-reviewed copy (per SEO plan).

### Phase 2: First Month (High Impact)
1. **Reconciliation reports** — Historical view, CSV export, running discrepancy totals.
2. **Multi-shift drawer support** — Multiple open/close cycles per day.
3. **Google Ads campaigns** — Launch Search and Performance Max campaigns.
4. **PayHero fee verification** — Confirm the fee table with PayHero.
5. **Customer statements** — P1 gap for hardware, wholesale, services.

### Phase 3: Ongoing
1. **Pack-and-loose units** — P1 gap for wholesale, retail, hardware, agrovet.
2. **Recipes/ingredients** — P1 gap for restaurants.
3. **Job cards** — P1 gap for service businesses.
4. **Remaining landing page content** — Switch on the other 39 sub-sectors in batches.

---

## 7. Appendix: M-Pesa Fee Comparison

### 7.1 Daraja (Buy Goods Till) — Merchant Charges

| Transaction Amount | Merchant Charge | Effective Rate |
|-------------------|----------------|----------------|
| KSh 0 – 200 | KSh 0 | 0% |
| KSh 201 – 500 | KSh 0 | 0% |
| KSh 501 – 36,363 | 0.55% | 0.55% |
| Above KSh 36,363 | Capped at KSh 200 | < 0.55% |

*Source: Safaricom M-Pesa Buy Goods merchant charges.*

### 7.2 PayHero — Merchant Charges

The PayHero fee structure is tiered by amount bands. See section 3.4 for the full table.

**Key comparison points:**
- On small transactions (below KSh 500), **Daraja is free** to the merchant. PayHero charges KSh 0–5.
- On medium transactions (KSh 500–5,000), **Daraja is cheaper** (0.55% flat vs. PayHero's tiered rates).
- On large transactions (above KSh 5,000), the gap widens in Daraja's favour.
- **PayHero's advantage** is speed of setup and fewer steps, not price.

### 7.3 Customer Charges

- **Daraja Buy Goods:** Free for the customer.
- **PayHero:** Free for the customer (the merchant bears the fee).

This is important for the trust screen: the customer never pays extra. The fee comes from what the business receives.

---

*Document ends. Next step: owner review of sections 5 (gaps & questions) and 6 (priorities), then implementation begins.*
