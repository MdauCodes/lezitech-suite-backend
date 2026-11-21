# BizNez: Revised Plan Strategy — Making Growth and Pro Compelling

**Written:** 2026-11-12
**Status:** Design document — for discussion and implementation planning

---

## 1. The Problem

The current plan structure has a critical flaw: **Growth and Pro offer only "more of everything" but no unique features.**

| Current Differentiator | Lite | Growth | Pro |
|----------------------|------|--------|-----|
| Price | KSh 2,650 | KSh 4,500 | KSh 9,000 |
| Staff | 3 | 8 | 30 |
| Branches | 1 | 3 | 10 |
| Items | 500 | 5,000 | Unlimited |
| Products | 1 (POS or CRM or ERP) | 2 | 3 |

**Every plan gets the same features:**
- Sales reports, back-office reports, CRM reports
- Tax estimation (VAT, Turnover Tax, Tourism Levy)
- Commission tracking and payouts
- Loyalty program
- Shift/till management
- M-Pesa integration (Daraja + PayHero)
- Stock management with batches
- CRM pipeline and leads
- Customer credit tracking
- Receipts (print, email, WhatsApp)
- Activity logs
- All sector-specific features

**Result:** A business on Lite has zero reason to upgrade. They'll only upgrade when they outgrow the numeric limits (more than 3 staff, more than 1 branch), which is a slow, organic trigger — not a feature-driven one.

**Realistic plan distribution:** 90%+ Lite, 9% Growth, 1% Pro (not the assumed 60/30/10).

---

## 2. Design Principles for the New Structure

1. **Each plan must have unique features** that the lower plans don't have — not just higher limits.
2. **The unique features must solve real pain points** that grow with the business.
3. **Lite must remain compelling** on its own — it's the entry point and the majority of customers.
4. **Upgrade triggers must be feature-driven** — "I need X feature" not "I need more staff slots."
5. **The product restriction on Lite is kept** — 1 product (POS or CRM or ERP), not all three. This is the strongest existing differentiator.

---

## 3. Revised Plan Structure

### 3.1 Lite — "Start Selling" (KSh 2,650/month)

**For:** Single-branch SMEs with 3 or fewer staff who need one core system.

| Category | Included |
|----------|----------|
| **Products** | **1 product**: POS **or** CRM **or** ERP (owner chooses) |
| Staff | 3 users, each with their own cash drawer |
| Branches | 1 |
| Items | 500 |
| **Core POS** | Sales, orders, receipts, M-Pesa STK, cash, card, split payments, pay-later |
| **Shifts** | Open/close drawer, per-staff cash tracking, basic reconciliation |
| **Stock** | Basic stock tracking, batches, expiry alerts |
| **CRM** | Customer records, basic credit tracking |
| **Reports** | Sales by day, by item, by staff, by payment method |
| **Tax** | Basic tax summary (VAT or Turnover Tax estimate) |
| **M-Pesa** | Daraja or PayHero integration, STK prompts |
| **Offline** | Offline sales with sync |
| **Sector pack** | Personalised screens, vocabulary, sample data |

**Not included (upgrade triggers):**
- ❌ Back-office ERP reports (stock value, purchases, margin by category, debtors/creditors)
- ❌ CRM pipeline and deals
- ❌ Commission tracking
- ❌ Loyalty program
- ❌ Tax reports with filing dates and daily breakdowns
- ❌ Customer statements and ageing reports
- ❌ Multi-branch reports
- ❌ Advanced stock (purchase orders, supplier management)
- ❌ Restaurant table ordering and kitchen display
- ❌ Tips management
- ❌ Activity audit log
- ❌ Data export (CSV/PDF)
- ❌ Priority support

**Yearly:** KSh 26,500 (2 months free)
**Intro offer:** First month KSh 1,000

---

### 3.2 Growth — "Grow Your Business" (KSh 4,500/month)

**For:** Growing businesses with multiple staff, needing deeper insights and team management.

| Category | Included |
|----------|----------|
| **Everything in Lite, plus:** | |
| **Products** | **Any 2 products**: POS + CRM, or POS + ERP, or CRM + ERP |
| Staff | 8 users, each with their own cash drawer |
| Branches | 3 |
| Items | 5,000 |
| **Back-Office ERP** | ✅ Stock value reports, purchases by supplier, margin by category, written-off stock, debtors & creditors |
| **CRM Pipeline** | ✅ Deals, pipeline stages, won/lost tracking, lead management |
| **Commission Tracking** | ✅ Per-staff commission rates, earned/paid/owed, payout recording |
| **Advanced Tax Reports** | ✅ Daily/monthly tax breakdown, filing dates, per-day savings guide, Tourism Levy |
| **Customer Statements** | ✅ Customer account statements, ageing analysis, credit limits |
| **Purchase Orders** | ✅ Supplier management, purchase orders, received tracking |
| **Restaurant Features** | ✅ Table ordering, kitchen display, order tracking (for hospitality sectors) |
| **Tips Management** | ✅ Tip tracking, per-waitress earnings, admin-configured distribution |
| **Loyalty Program** | ✅ Customer points, adjustments, redemption at checkout |
| **Data Export** | ✅ CSV export for all reports |
| **Spreadsheet Import** | ✅ Bulk item import via CSV |
| **Support** | Standard email support |

**Not included (upgrade triggers):**
- ❌ Third product (all 3 products together)
- ❌ Multi-branch consolidated reports
- ❌ Activity audit log
- ❌ PDF report generation
- ❌ Custom branding on receipts
- ❌ Priority support
- ❌ Dedicated account manager

**Yearly:** KSh 45,000 (2 months free)
**Intro offer:** First month KSh 2,500

---

### 3.3 Pro — "Full Operations" (KSh 9,000/month)

**For:** Multi-branch operations, chains, and businesses that need the complete suite.

| Category | Included |
|----------|----------|
| **Everything in Growth, plus:** | |
| **Products** | **All 3 products**: POS + CRM + ERP together |
| Staff | 30 users, each with their own cash drawer |
| Branches | 10 |
| Items | Unlimited |
| **Multi-Branch Reports** | ✅ Consolidated reports across all branches, branch-to-branch comparison, inter-branch transfers |
| **Activity Audit Log** | ✅ Full audit trail: who did what, when, with before/after values |
| **PDF Reports** | ✅ Professional PDF reports with business logo, downloadable and shareable |
| **Custom Receipt Branding** | ✅ Business logo, tagline, custom footer on receipts |
| **Advanced Stock** | ✅ Stock transfers between branches, inter-branch inventory management |
| **ETR via Email** | ✅ Automatic ETR PDF sent to customer after each payment (KSh 4/ETR billed monthly) |
| **API Access** | ✅ Read-only API for integrations (accounting software, custom tools) |
| **Priority Support** | ✅ Priority email and WhatsApp support, faster response times |
| **Dedicated Account Manager** | ✅ Named LeziTech contact for onboarding and issues |
| **Custom Work Discounts** | ✅ Discounted rates on custom integrations, websites, or features |

**Yearly:** KSh 90,000 (2 months free)
**Intro offer:** First month KSh 5,500

---

## 4. Feature Matrix — Quick Reference

| Feature | Lite | Growth | Pro |
|---------|------|--------|-----|
| **Products** | 1 | 2 | All 3 |
| **Staff** | 3 | 8 | 30 |
| **Branches** | 1 | 3 | 10 |
| **Items** | 500 | 5,000 | Unlimited |
| **Core POS** | ✅ | ✅ | ✅ |
| **M-Pesa STK** | ✅ | ✅ | ✅ |
| **Offline mode** | ✅ | ✅ | ✅ |
| **Sector packs** | ✅ | ✅ | ✅ |
| **Shift/till management** | ✅ | ✅ | ✅ |
| **Basic sales reports** | ✅ | ✅ | ✅ |
| **Basic tax summary** | ✅ | ✅ | ✅ |
| **Stock tracking** | ✅ | ✅ | ✅ |
| **Customer records** | ✅ | ✅ | ✅ |
| **Back-office ERP reports** | ❌ | ✅ | ✅ |
| **CRM pipeline & deals** | ❌ | ✅ | ✅ |
| **Commission tracking** | ❌ | ✅ | ✅ |
| **Advanced tax reports** | ❌ | ✅ | ✅ |
| **Customer statements** | ❌ | ✅ | ✅ |
| **Purchase orders** | ❌ | ✅ | ✅ |
| **Restaurant table ordering** | ❌ | ✅ | ✅ |
| **Kitchen display** | ❌ | ✅ | ✅ |
| **Tips management** | ❌ | ✅ | ✅ |
| **Loyalty program** | ❌ | ✅ | ✅ |
| **Data export (CSV)** | ❌ | ✅ | ✅ |
| **Spreadsheet import** | ❌ | ✅ | ✅ |
| **Multi-branch reports** | ❌ | ❌ | ✅ |
| **Activity audit log** | ❌ | ❌ | ✅ |
| **PDF reports** | ❌ | ❌ | ✅ |
| **Custom receipt branding** | ❌ | ❌ | ✅ |
| **ETR via email** | ❌ | ❌ | ✅ |
| **API access** | ❌ | ❌ | ✅ |
| **Priority support** | ❌ | ❌ | ✅ |
| **Account manager** | ❌ | ❌ | ✅ |

---

## 5. Why This Works

### 5.1 Lite → Growth Upgrade Triggers

A Lite customer will want to upgrade to Growth when they need any of these:

1. **"I need to track my staff commissions"** — Salon, barbershop, mechanic, tutor. The moment they have staff who earn a cut of their sales, they need commission tracking.
2. **"I need to see my profit margins"** — Retail, wholesale, hardware. When they care about cost vs. selling price by category, they need back-office ERP reports.
3. **"I need to manage customer credit properly"** — Hardware, wholesale, services. When they have customers who buy on credit and need statements, they need customer statements.
4. **"I need a CRM pipeline"** — Services, tours, property. When they have leads that need follow-up, they need the deal pipeline.
5. **"I need table ordering for my restaurant"** — Hospitality. When they want the QR-to-kitchen flow, they need Growth.
6. **"I need my customers to earn loyalty points"** — Any sector. When they want repeat business incentives, they need loyalty.
7. **"I need to import my items from Excel"** — Any sector with many items. When they have 200+ items to add, spreadsheet import saves hours.
8. **"I need the second product"** — Lite only allows 1 product. When they need both POS and CRM, or POS and ERP, they must upgrade.

### 5.2 Growth → Pro Upgrade Triggers

A Growth customer will want to upgrade to Pro when they need any of these:

1. **"I need all 3 products"** — POS + CRM + ERP together. Growth only allows 2.
2. **"I need consolidated reports across branches"** — Multi-branch retail, hotel chains. When they have 3+ branches and want one view.
3. **"I need an audit trail"** — Any business with compliance needs. Who changed what, when, and what was the before/after.
4. **"I need professional PDF reports"** — When they need to share reports with accountants, investors, or partners.
5. **"I need ETR sent to customers"** — When KRA enforcement increases and customers expect automatic ETR.
6. **"I need API access"** — When they want to integrate with external accounting software or custom tools.
7. **"I need priority support"** — When downtime costs more than the upgrade.

---

## 6. Revised Financial Projections

### 6.1 Realistic Plan Distribution

With the new feature-driven differentiation, the plan mix shifts:

| Plan | Old Assumption | New Realistic | Rationale |
|------|---------------|---------------|-----------|
| **Lite** | 60% | **50%** | Still the entry point, but feature gaps drive upgrades |
| **Growth** | 30% | **40%** | Commissions, ERP reports, CRM pipeline, table ordering are strong upgrade triggers |
| **Pro** | 10% | **10%** | Multi-branch and audit log are niche; 10% is realistic for chains and larger operations |

**Revised Weighted ARPU:**
- (50% × KSh 2,650) + (40% × KSh 4,500) + (10% × KSh 9,000)
- = KSh 1,325 + KSh 1,800 + KSh 900
- = **KSh 4,025/month per paying customer**

**Improvement:** ARPU increases from KSh 3,840 to KSh 4,025 (+4.8%) even before any price changes.

### 6.2 Why the Old Projections Were Overly Optimistic

The old 60/30/10 split assumed businesses would naturally distribute across plans. But without feature differentiation:

- **Realistic old distribution:** 90% Lite, 9% Growth, 1% Pro
- **Old ARPU with real distribution:** (90% × 2,650) + (9% × 4,500) + (1% × 9,000) = KSh 2,813
- **This is 27% lower than the projected KSh 3,840**

The new feature-driven structure makes the 50/40/10 distribution achievable because:
- Growth has 12 unique features that Lite doesn't have
- Pro has 7 unique features that Growth doesn't have
- Each feature solves a real, identifiable pain point

### 6.3 Impact on Year 1 Projections

Using the revised ARPU of KSh 4,025 (instead of KSh 3,840):

| Metric | Old Projection | Revised Projection | Change |
|--------|---------------|-------------------|--------|
| **ARPU** | KSh 3,840 | KSh 4,025 | +4.8% |
| **Month 3 MRR** (realistic, 187 customers) | KSh 718,080 | KSh 752,675 | +KSh 34,595 |
| **Month 12 MRR** (realistic, 1,265 customers) | KSh 4,857,600 | KSh 5,091,625 | +KSh 234,025 |
| **Year 1 total profit** | ~KSh 25M | ~KSh 25.6M | +KSh 0.6M |

The direct ARPU impact is modest (+4.8%), but the **indirect impact is larger**:
- Feature-driven upgrades happen faster — a customer who needs commissions will upgrade in month 1, not wait until they need more staff slots.
- Lower churn — customers on Growth/Pro have more reasons to stay (more features invested in).
- Higher LTV — customers who upgrade to Growth have 2.3× the LTV of Lite customers.

---

## 7. Implementation Strategy

### 7.1 What Needs to Change

#### Database
- No schema changes needed. All features already exist in the database.
- Plan limits in `PlanLimits.java` need to be extended to include feature flags.

#### Backend
- **PlanLimits.java:** Add feature-level gating. Instead of just checking numeric limits, check whether a feature is available on the current plan.
- **SettingsController.java:** Update `PLANS` list to include feature flags for each plan.
- **ReportController.java:** Add plan checks on `/back-office` and `/customers` endpoints — return 403 for Lite with a clear upgrade message.
- **TaxController.java:** Add plan check — Lite gets basic summary only; Growth and Pro get full daily/monthly breakdown.
- **CommissionController.java:** Add plan check — Lite gets 403 with upgrade message.
- **LoyaltyController.java:** Add plan check — Lite gets 403 with upgrade message.
- **RestaurantController.java:** Add plan check — table ordering and kitchen display require Growth+.
- **BoardScreenController.java:** Add plan check — order board screens require Growth+.
- **PurchaseController.java:** Add plan check — purchase orders require Growth+.
- **CrmController.java:** Add plan check — pipeline and deals require Growth+.
- **ActivityController.java:** Add plan check — full audit log requires Pro.
- **ReceiptController.java:** Add plan check — custom branding requires Pro.

#### Frontend
- **Billing/Plans page:** Show feature matrix with checkmarks and crosses. Highlight what the customer gets on their current plan vs. what they'd get on the next plan.
- **Upgrade prompts:** When a Lite user clicks on a Growth-only feature, show a non-disruptive banner: "This feature is available on Growth. Upgrade to unlock it."
- **Settings page:** Grey out Growth/Pro-only settings for Lite users with an "Upgrade to unlock" tooltip.

#### New Component: FeatureGuard
A simple annotation or interceptor that checks the current plan against the required plan for a feature:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePlan {
    String value(); // "LITE", "GROWTH", or "PRO"
    String featureName(); // Human-readable name for the error message
}
```

When a Lite user hits a Growth-only endpoint, they get:
```json
{
  "error": "PLAN_LIMIT",
  "message": "Commission tracking is available on Growth and Pro plans. Upgrade to unlock it.",
  "feature": "commission_tracking",
  "requiredPlan": "GROWTH"
}
```

### 7.2 Phased Rollout

**Phase 1: Foundation (Week 1)**
- Create `FeatureGuard` annotation and interceptor.
- Update `PlanLimits.java` with feature-level checks.
- Update `SettingsController.PLANS` with feature flags.

**Phase 2: Gate the Features (Week 1–2)**
- Add plan checks to all controllers listed above.
- Test that Lite users can't access Growth/Pro features.
- Test that upgrade messages are clear and actionable.

**Phase 3: Frontend Updates (Week 2)**
- Update the billing/plans page with feature matrix.
- Add upgrade prompts on feature access attempts.
- Grey out unavailable settings.

**Phase 4: Testing (Week 2)**
- End-to-end test: Lite user tries to access each Growth/Pro feature.
- End-to-end test: Growth user tries to access each Pro feature.
- Test upgrade flow: Lite → Growth → Pro and verify features unlock.

---

## 8. Landing Page Implications

Each sector landing page should reflect the plan structure:

### Hero Section
- Show Lite price prominently: "From KSh 2,650/month"
- Add note: "Start with one system. Upgrade when you need more."

### Pricing Section
- Show all three plans with feature highlights.
- For each sector, add a note about which plan most businesses in that sector choose:
  - **Restaurant:** "Most restaurants start on Lite for basic POS and upgrade to Growth for table ordering and kitchen display."
  - **Salon:** "Most salons upgrade to Growth for commission tracking — their staff earn a cut of each service."
  - **Hardware:** "Most hardware shops upgrade to Growth for customer statements and credit management."
  - **Retail:** "Most retail shops start on Lite and upgrade to Growth for back-office reports and margin tracking."

### FAQ Section
Add: "Can I upgrade later?" → "Yes. You can upgrade from Lite to Growth or Pro at any time. Your data stays intact, and you only pay the difference for the remaining days."

---

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Lite feels too limited** | High bounce rate at signup | Lite still includes core POS, M-Pesa, shifts, basic reports — it's a complete system for 1 product |
| **Growth feels expensive** | Low Lite → Growth conversion | KSh 4,500 is still below competitors. The 12 unique features provide clear value. Intro offer (KSh 2,500 first month) lowers the barrier. |
| **Pro is still too niche** | Low Growth → Pro conversion | 10% Pro adoption is realistic. Pro targets chains and multi-branch operations — a smaller but higher-value segment. |
| **Existing Lite customers feel cheated** | Churn spike on rollout | Feature gating applies to new signups only. Existing customers keep their current access for 30 days, then gradually transition. |
| **Feature gating is confusing** | Support burden | Clear upgrade messages, feature matrix on billing page, and "Upgrade to unlock" tooltips make it obvious. |

---

## 10. Summary

### What Changed

| Aspect | Old Structure | New Structure |
|--------|--------------|---------------|
| **Differentiation** | Numeric limits only | Unique features per tier + numeric limits |
| **Lite unique value** | Same features, fewer slots | Complete 1-product system with core features |
| **Growth unique value** | More staff, more branches | 12 unique features: ERP reports, CRM pipeline, commissions, tax reports, customer statements, purchase orders, table ordering, tips, loyalty, CSV export, spreadsheet import, 2nd product |
| **Pro unique value** | More of everything | 7 unique features: multi-branch reports, audit log, PDF reports, custom branding, ETR, API access, priority support |
| **Upgrade trigger** | "I need more staff slots" | "I need X feature" (feature-driven) |
| **Plan mix** | 90/9/1 (realistic) | 50/40/10 (achievable) |
| **ARPU** | KSh 2,813 (realistic old) | KSh 4,025 (new) |

### Why This Is Better

1. **Every plan has a clear identity.** Lite = "Start Selling." Growth = "Grow Your Business." Pro = "Full Operations."
2. **Upgrade is feature-driven.** A salon owner doesn't upgrade because they need "more staff slots" — they upgrade because they need commission tracking. This is a stronger, more immediate motivator.
3. **Lite remains compelling.** A single-branch minimart with 3 staff gets everything they need: POS, M-Pesa, shifts, basic reports, stock tracking. They're not missing anything critical.
4. **Growth is the sweet spot.** With 12 unique features, Growth is where the real value is. Commissions, ERP reports, CRM pipeline, table ordering — these are features businesses actively want.
5. **Pro is for serious operations.** Multi-branch reports, audit logs, PDF reports, ETR — these are features that chains, hotels, and larger retailers need for compliance and management.
6. **ARPU increases.** Even a modest shift from 90/9/1 to 50/40/10 raises ARPU by 43% (KSh 2,813 → KSh 4,025).

### Bottom Line

The old plan structure was a **commodity** — every plan had the same features, just different limits. The new structure is a **ladder** — each rung offers something the one below doesn't, and the customer can clearly see what they're missing and why they should climb.

This makes the financial projections more realistic and the business model more sustainable.

---

*Document ends. Next step: owner review and approval, then implementation planning.*
