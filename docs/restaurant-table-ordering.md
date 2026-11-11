# Restaurant Table-Ordering Workflow: QR-to-Kitchen-to-Pay

Written 2026-11-11. Status: **design final — all decisions captured**.

This document designs the end-to-end restaurant table-ordering workflow where a customer scans a QR code at their table, browses the menu, places an order, watches it progress through the kitchen, and pays via M-Pesa STK — all without waiting for a waiter to take the order. All design decisions have been discussed and confirmed with the product owner.

---

## Build progress (kept current while building)

How it is built and in what order: `plan-restaurant-and-tills.md`. Where this document disagrees with
`decisions.md` (ETR is free and uploaded by the business; QR codes open the app's address; the restaurant chooses
when diners pay; support access only when the owner grants it), `decisions.md` wins.

| Package | What | Status |
|---|---|---|
| A | Database (V18), R2 file storage, item photo upload, ordering, tip and loyalty settings | Built 2026-11-11 |
| B | QR per table and printable cards, order from the phone, review, waiter assignment, pickup code | Next |
| C | Estimated time, unavailable items, kitchen substitutes, live tracking, waiters' tables | Planned |
| D | Pay from the phone (single and split), tips, rating | Planned |
| E | Group orders | Planned |
| F | Photos and allergens on the menu, board screens per area | Planned |
| G | Loyalty points | Planned |

---

## 1. What Already Exists

The platform already has a strong restaurant foundation. Before designing what's new, here's what's **already built**:

| Component | What It Does | API Endpoint |
|-----------|-------------|--------------|
| **Floor Plan** | Floors with dining tables, each with label, seats, shape, position | `GET /api/floors` |
| **Kitchen Display** | Tickets grouped by order, showing lines by station (kitchen/bar) | `GET /api/kitchen?station=kitchen` |
| **Kitchen Status Flow** | PENDING → NEW → PREPARING → READY → SERVED per order line | `POST /api/kitchen/lines/{id}` |
| **Kitchen Bump** | Advance an entire ticket one step (NEW→PREPARING→READY→SERVED) | `POST /api/kitchen/orders/{orderId}/bump` |
| **Public Menu** | Customer-facing menu with categories, items, prices, add-ons, sold-out flag | `GET /api/public/menu/{slug}` |
| **Order Tracking** | Customer-facing order status with stage (OPEN, RECEIVED, PREPARING, READY, DONE) | `GET /api/public/track/{slug}/{token}` |
| **Order Board** | TV screen showing orders being prepared and orders ready to collect | `GET /api/public/board/{slug}?k={key}` |
| **Table-Aware Orders** | Orders linked to `dining_tables`, with `table_id`, `guests`, `customer_label` | `orders` table |
| **Station Routing** | Items tagged with `station` (kitchen/bar), lines routed accordingly | `items.station` |
| **Add-Ons** | Item options (size, extras) as JSON on the item, carried through to order lines | `items.options` |
| **Split Bill** | Lines can be moved to a new bill on the same table | `POST /api/orders/{id}/split` |
| **Send to Kitchen** | Fire pending lines to the kitchen display, optionally by course | `POST /api/orders/{id}/send` |
| **Public Token** | Each order has a `public_token` for customer-facing tracking | `orders.public_token` |
| **Display Key** | Each tenant has a `display_key` for securing the public board | `tenants.display_key` |
| **Customer Label** | Order can carry a `customer_label` for identification on the board | `orders.customer_label` |
| **Ready Timestamp** | Order gets `ready_at` when all lines reach READY status | `orders.ready_at` |

**What is NOT yet built (this document):**

1. **QR code per table** — a scannable code that encodes the table ID and links to the menu.
2. **Customer self-ordering** — placing an order directly from the public menu page without staff.
3. **Order review page** — customer reviews order before it goes to the kitchen (no modification after placement).
4. **Order progress visibility** — the customer sees their order stage in real time from their phone.
5. **Estimated time** — hybrid: system suggests based on history, kitchen can override.
6. **Self-payment** — the customer pays via M-Pesa STK from their phone when the order is ready.
7. **Split payment** — multiple phones share the bill (equal, custom amount, or percentage).
8. **Order verification** — ensuring the person who pays is the person who ordered.
9. **QR card design** — branded triangular card downloadable from the admin panel.
10. **Waitress assignment** — load-based weighted score assigns each order to a waitress.
11. **Batch ordering** — one person orders across multiple tables for large groups.
12. **ETR via email** — KSh 4 per ETR sent, billed monthly.
13. **Tips** — configurable presets with custom entry, admin-configurable distribution.
14. **Loyalty program** — opt-in, configured via setup wizard, fully custom rules.
15. **Menu photos** — stored on Cloudflare R2.
16. **Allergen info** — expandable section per item.
17. **Order board screens** — admin-created named screens with auto-creation suggestions.
18. **Support access** — time-limited full admin access (5-hour window, stoppable by admin).
19. **Kitchen rejection** — kitchen rejects with substitute option, waitress follows up.

---

## 2. The End-to-End Workflow

### 2.1 The QR Card Design

Each table gets a **triangular printed card** (or small stand) designed and downloaded from the restaurant's admin panel. The card is **owned wholly by the restaurant** — LeziTech provides only a small branding line at the bottom.

**Card design elements (generated per restaurant):**
- **Restaurant logo** (uploaded by admin).
- **Brand colors** applied to the card background and text.
- **Business name** prominently displayed.
- **Tagline** (set by the restaurant admin).
- **Side A — Menu & Order** (larger QR, labeled "Scan to Order"):
  - URL: `https://lezitech.site/s/{slug}/table/{tableId}`
  - Opens the public menu, pre-linked to this table.
- **Side B — Track & Pay** (smaller QR, labeled "Track & Pay"):
  - URL: Generated after order placement. The customer gets this on the confirmation screen.
  - URL: `https://lezitech.site/s/{slug}/track/{token}`
- **Guiding text:** Simple instructions like "Scan to browse menu, order, and pay. You can also add a tip."
- **BizNez branding** (small, at the bottom): **BizNez by LeziTech** · "Legitimacy in Business Tech" · 0794 206 566.

**How the restaurant gets the cards:**
1. Admin goes to the floor plan view.
2. Clicks "Download QR Cards" — generates a printable PDF with all table QR codes.
3. Each card is pre-designed with the restaurant's branding.
4. The restaurant prints the cards (triangular stands or table inserts).

**API addition:**
```
GET /api/tables/qr-cards-pdf
Returns: PDF with all table QR codes, branded with restaurant logo, colors, and tagline.
```

### 2.2 Side A vs Side B

**Side A — Menu & Order** (larger QR, labeled "Scan to Order"):
- URL: `https://lezitech.site/s/{slug}/table/{tableId}`
- Opens the public menu, pre-linked to this table.
- The customer browses, adds items to cart, and places the order.

**Side B — Track & Pay** (smaller QR, labeled "Track & Pay"):
- URL: Generated after order placement. The customer gets this on the confirmation screen after ordering, and it can also be printed on the receipt.
- URL: `https://lezitech.site/s/{slug}/track/{token}`
- Shows the order progress and the pay button when ready.

**How it works in practice:**
1. Customer sits down, scans Side A.
2. They browse the menu, add items, optionally enter their name.
3. They review the order on a confirmation page (no modification after sending).
4. They place the order — the kitchen sees it immediately.
5. A waitress is auto-assigned via load-based weighted scoring.
6. On the confirmation screen after ordering, the customer sees their **tracking QR code** (Side B equivalent) and a "Track My Order" button.
7. They can tap "Track My Order" to see the progress, or scan Side B later (if they printed the receipt with the QR) to get back to the tracking page.
8. When the order is READY, a "Pay Now" button appears.
9. Customer pays via M-Pesa STK prompt (or splits across multiple phones).
10. The assigned waitress sees on their tablet that the order is paid and ready to serve.

**Why two sides?**
- Side A is always scannable — it's the entry point.
- Side B is generated per order — it can't be pre-printed. The customer gets it digitally after ordering. If they want a physical copy, the receipt (printed or WhatsApp) carries the tracking link.

### 2.3 The Customer Journey (Step by Step)

#### Step 1: Scan & Browse

Customer scans the QR code at the table. Their phone opens:

```
https://lezitech.site/s/{slug}/table/{tableId}
```

The page loads the **public menu** (already built: `GET /api/public/menu/{slug}`) with the table ID stored in the session/localStorage.

**Menu page shows:**
- Restaurant name, logo, brand color (already built).
- Categories with items, prices, add-ons, **photos** (Cloudflare R2 stored).
- Sold-out items greyed out (already built).
- **"Table {label}"** badge at the top so the customer knows they're ordering for the right table.
- **Allergen info** — expandable section per item (tap to see allergens).
- Cart icon in the corner.

**Batch ordering mode:** If the customer is with a large group (e.g., school group covering multiple tables), they can select "Group Order" mode before browsing:
1. Select the tables the group occupies.
2. Build orders per table — each table gets its own set of items and quantities.
3. One combined bill for the entire group.
4. The person who scanned can choose payment split: one payer, equal split across N phones, or custom amount/percentage per phone.

#### Step 2: Build the Order

Customer taps items to add them to the cart. For each item:
- **Photo** displayed prominently (if uploaded by the restaurant).
- Add-ons dialog appears if the item has options (size, extras) — already supported by `items.options`.
- Special instructions text field (e.g., "no onions", "extra spicy").
- Quantity selector.
- **Allergen info** — expandable button showing allergens (nuts, gluten, dairy, shellfish, etc.).

**Optional: Customer enters their name and phone.**
- Name field: "Your name (so we can call out your order):" — populates `customer_label` on the order.
- Phone field: "Your M-Pesa number (for payment):" — pre-fills the payment step.
- If the customer skips the name, the table label is used for identification.

#### Step 3: Review Order

Before the order goes to the kitchen, the customer sees a **review page**:

```
Review Your Order — Table 5

  1× Nyama Choma — no onions    KSh 850
  2× Ugali                       KSh 200
  1× Coca-Cola                   KSh 200
─────────────────────────────────────
  Subtotal                       KSh 1,250

  ☐ I want to receive my ETR via email (KSh 4)

[← Edit Order]    [Send to Kitchen]
```

**Key rules:**
- **No modification after sending.** Once the order is sent to the kitchen, it cannot be changed. The review page is the last chance to edit.
- **ETR checkbox:** Customer can opt in to receive an ETR (Electronic Tax Receipt) via email for KSh 4. If checked, the ETR PDF is generated and emailed immediately after payment. If Brevo quota is exhausted, it's queued and sent early the next morning.

#### Step 4: Place the Order

Customer taps "Send to Kitchen". The system:

1. Creates an order with `channel = 'DINE_IN'`, `table_id = {tableId}`, `customer_label = {name or null}`, `customer_phone = {phone or null}`.
2. Creates order lines with `kitchen_status = 'NEW'`, `station` from the item, `note` from special instructions.
3. The order is immediately visible on the kitchen display.
4. **Waitress auto-assigned:** System assigns the order to the waitress with the lowest weighted load score (see §3.2). If a waitress manually entered the order, they keep it.
5. The customer sees a confirmation screen:

```
✅ Order #0042 placed!

Your order:
  1× Nyama Choma — no onions
  2× Ugali
  1× Coca-Cola

You'll receive your ETR via email after payment.

[Track My Order]
```

The "Track My Order" button takes them to the tracking page (Step 5).

#### Step 5: Track Progress

Customer taps "Track My Order" or scans the tracking QR from their receipt. Their phone opens:

```
https://lezitech.site/s/{slug}/track/{token}
```

The page loads the **public tracking** (already built: `GET /api/public/track/{slug}/{token}`) and shows:

```
Order #0042 — Table 5

📋 Your Order
  1× Nyama Choma — no onions
  2× Ugali
  1× Coca-Cola

🕐 Status: Kitchen is preparing your food

Progress:
  ● Placed (3:42 PM)
  ● Received (3:43 PM)
  ● Preparing (3:44 PM) ← current
  ○ Ready
  ○ Served

Estimated: ~15 minutes
```

**The stage is computed server-side** (already built in `PublicService.track()`):
- OPEN → order created, not yet sent to kitchen
- RECEIVED → kitchen sees it (NEW status)
- PREPARING → kitchen is cooking (PREPARING status)
- READY → food is ready, waiting for payment/service
- DONE → all served
- CANCELLED → order voided

**Real-time updates:** The frontend polls the tracking endpoint every 10 seconds (or uses Server-Sent Events when available) to update the stage without the customer refreshing.

#### Step 6: Kitchen Workflow

The kitchen display (already built) shows the new ticket:

```
┌─────────────────────────────────┐
│ Order #0042   Table 5           │
│ Waitress: Jane  |  3:42 PM      │
├─────────────────────────────────┤
│ ☐ Nyama Choma ×1  (no onions)  │
│ ☐ Ugali ×2                    │
└─────────────────────────────────┘
```

The bar display shows:

```
┌─────────────────────────────────┐
│ Order #0042   Table 5           │
│ 3:42 PM                         │
├─────────────────────────────────┤
│ ☐ Coca-Cola ×1                  │
└─────────────────────────────────┘
```

**Kitchen staff actions (already built):**
- Tap a line to advance: NEW → PREPARING → READY → SERVED.
- Or tap "Bump" to advance the entire ticket one step.
- **Estimated time:** System suggests an estimate based on historical data (e.g., "Nyama Choma usually takes 20 minutes"). Kitchen can override it manually.
- **Mark item unavailable:** Kitchen/waitress can mark a menu item as temporarily unavailable or "procurement on the way" — customers see it as sold out.
- **Reject with substitute:** If an item is out of stock, the kitchen can reject the order line and suggest a substitute. The customer is notified on their tracking page and can approve or decline. If declined, a waitress follows up to explain.

#### Step 7: Order Ready — Pay

When all lines reach READY status, the order's `ready_at` is set (already built). The customer's tracking page updates:

```
✅ Your order is ready!

Total: KSh 1,250

[Pay with M-Pesa]  [Split Payment]
```

**Payment options:**

**Single payment (default):**
The "Pay with M-Pesa" button triggers an **M-Pesa STK push** (already built in the M-Pesa module) for the order total. The customer:
1. Enters their M-Pesa number (or it's pre-filled from the ordering step).
2. Gets the STK prompt on their phone.
3. Enters their PIN.
4. Payment is confirmed.

**Split payment:**
The "Split Payment" button opens a split payment dialog:
- **Equal split:** Enter number of phones (N), system divides the total equally.
- **Custom amount:** Enter phone number and amount for each payer.
- **Percentage split:** Enter phone number and percentage for each payer.

STK pushes are sent **sequentially** — one phone is prompted, the callback is processed (success or failure), then the next phone is prompted. If a payment fails, that phone is requeued at the end of the sequence after all others have been prompted.

**Tips:**
After the amount is confirmed, the customer can add a tip:
- **Custom amount** (first option, default).
- **Presets:** KSh 300, 500, 1,000, 3,000 (restaurant can configure these).

**Tip distribution** (admin-configured):
- Admin sets percentages: e.g., kitchen 45%, business 10%, waitress/waiter 45%.
- System tracks per-waitress tip earnings and per-kitchen tip pool.
- Admin can view tip reports: per waitress, per order, total tips, and distribution breakdown.

#### Step 8: After Payment — Rating & ETR

**After payment confirmation:**
- The order status changes to PAID.
- The tracking page shows: "✅ Paid — your food is being brought to you."
- **Rating prompt** appears on the payment confirmation page: "How was your experience?" (5-star rating with optional comment).
- **ETR email** is sent immediately if the customer opted in (KSh 4 per ETR). If Brevo quota is exhausted, the ETR is queued and sent early the next morning.
- The assigned waitress's tablet shows: "Order #0042 — Table 5 — PAID — Ready to serve."
- The kitchen display marks the order as SERVED.

**Loyalty points:** If the restaurant has loyalty enabled (opt-in, configured via setup wizard), and the customer entered their phone number, the system awards points based on the fully custom rules set by the admin.

#### Step 9: Service

The assigned waitress sees on their tablet (floor plan view — already built) that Table 5's order is paid and ready. They serve the food.

---

## 3. What's New — Detailed Design

### 3.1 QR Code Generation per Table

**Database:** No new table needed. The QR code is generated from existing data:
- Table ID → `dining_tables.id`
- Tenant slug → `tenants.slug`

**QR Code URL pattern:**
- Menu/Order: `https://lezitech.site/s/{slug}/table/{tableId}`
- Track/Pay: `https://lezitech.site/s/{slug}/track/{publicToken}` (generated per order)

**QR Code generation:**
- The frontend generates the QR code image for each table in the floor plan view.
- The restaurant prints these QR codes (one per table) as triangular cards or small stands.
- A "Download QR Cards" button in the floor plan view generates a printable PDF with all table QR codes, branded with the restaurant's logo, brand colors, tagline, and LeziTech's small branding at the bottom.

**API addition:**
```
GET /api/tables/{id}/qr-url
Returns: { "menuUrl": "...", "slug": "...", "tableId": "...", "tableLabel": "..." }

GET /api/tables/qr-cards-pdf
Returns: PDF with all table QR codes, branded with restaurant logo, colors, and tagline.
```

The frontend uses a QR code library (e.g., `qrcode.react`) to render the image. The PDF is generated server-side using a templating library (e.g., Apache PDFBox or iText).

### 3.2 Customer Self-Ordering

**New API endpoint:**
```
POST /api/public/order
Body: {
  "slug": "my-restaurant",
  "tableId": "uuid",
  "customerLabel": "Brian",       // optional
  "phone": "0712345678",          // optional, for M-Pesa later
  "wantEtr": true,                // optional, KSh 4 fee if true
  "lines": [
    { "itemId": "uuid", "qty": 1, "options": [...], "note": "no onions" },
    { "itemId": "uuid", "qty": 2, "options": [], "note": "" }
  ]
}
Returns: { "orderId": "uuid", "orderNumber": 42, "publicToken": "uuid", "total": 1254, "trackingUrl": "..." }
```

**How it works:**
1. The endpoint validates the table exists and belongs to the tenant.
2. Creates an order with `channel = 'DINE_IN'`, `table_id`, `customer_label`, `public_token`.
3. Creates order lines with `kitchen_status = 'NEW'` (sent directly to kitchen, no intermediate PENDING state).
4. Sets `sent_at = now()` so the kitchen sees it immediately.
5. **Assigns a waitress** via load-based weighted scoring (see §3.7).
6. Returns the order details including the `public_token` for tracking.

**Security:**
- No authentication required — the customer is identified by the table QR code.
- Rate limiting: max 5 orders per table per hour to prevent abuse.
- The order is linked to the table, so even if someone places a fake order, it shows up on the kitchen display with the table number and the staff can reject it.

**No modification after placement.** The order is locked once sent to the kitchen. The review page (Step 3 of the customer journey) is the last chance to edit.

### 3.3 Batch Ordering (Group Orders)

For large groups (e.g., school groups covering multiple tables), one person can place orders across multiple tables:

**New API endpoint:**
```
POST /api/public/batch-order
Body: {
  "slug": "my-restaurant",
  "tableIds": ["uuid1", "uuid2", "uuid3"],
  "orders": [
    {
      "tableId": "uuid1",
      "lines": [
        { "itemId": "uuid", "qty": 10, "options": [], "note": "" }
      ]
    },
    {
      "tableId": "uuid2",
      "lines": [
        { "itemId": "uuid", "qty": 8, "options": [], "note": "" }
      ]
    }
  ],
  "paymentSplit": {
    "mode": "equal",              // "equal" | "custom" | "percentage"
    "payees": [
      { "phone": "0712345678", "amount": 5000 },  // for custom/percentage mode
      { "phone": "0798765432", "amount": 5000 }
    ]
  }
}
Returns: { "batchId": "uuid", "orders": [...], "total": 10000 }
```

**How it works:**
1. The person scanning selects "Group Order" mode.
2. They select the tables the group occupies.
3. They build orders per table — each table gets its own set of items and quantities.
4. One combined bill for the entire group.
5. Payment split is chosen: one payer, equal split across N phones, or custom amount/percentage per phone.
6. Each table's order is tracked independently on the order board.
7. The batch order creates multiple orders under one `batch_id` for reporting.

**Database change:**
```sql
ALTER TABLE orders ADD COLUMN batch_id uuid;
```

### 3.4 Estimated Time (Hybrid)

**Database change:**
```sql
ALTER TABLE orders ADD COLUMN estimated_ready_at timestamptz;
ALTER TABLE orders ADD COLUMN estimated_minutes integer;
```

**System suggests the estimate:**
Based on historical data, the system calculates the average preparation time for the items in the order. For example, if "Nyama Choma" usually takes 20 minutes and "Ugali" usually takes 10 minutes, the system suggests 20 minutes (the longest item).

**Kitchen can override:**
```
PATCH /api/orders/{id}/estimate
Body: { "estimatedMinutes": 15 }
Sets: estimated_ready_at = now() + interval '15 minutes'
```

**Customer sees the estimate:**
The tracking endpoint returns `estimatedReadyAt` and the frontend shows:
- "Estimated: ~15 minutes" (countdown timer).
- When the estimate passes, show: "Running a bit late — we'll serve you soon."

**Historical data table:**
```sql
CREATE TABLE item_prep_times (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  item_id uuid NOT NULL REFERENCES items(id),
  avg_minutes integer NOT NULL,
  sample_count integer NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

When an order is completed, the actual preparation time is recorded and used to update the average.

### 3.5 Self-Payment via M-Pesa STK

**The payment flow from the tracking page:**

When the order reaches READY status, the tracking page shows the "Pay with M-Pesa" button and optionally a "Split Payment" button. The customer:

1. Taps "Pay with M-Pesa" or "Split Payment".
2. For single payment: enters their M-Pesa number (or it's pre-filled from the ordering step).
3. For split payment: chooses equal split, custom amount, or percentage split.
4. Confirms the amount.
5. STK prompt is sent to their phone (sequentially for split payment).
6. They enter their PIN.
7. Callback confirms payment.
8. Order status changes to PAID when all portions are paid.

**New API endpoint:**
```
POST /api/public/order/{publicToken}/pay
Body: { "phone": "0712345678" }
Returns: { "status": "PENDING", "message": "STK prompt sent" }

POST /api/public/order/{publicToken}/pay-split
Body: {
  "mode": "equal",
  "payees": [
    { "phone": "0712345678", "amount": 500 },
    { "phone": "0798765432", "amount": 500 }
  ]
}
Returns: { "status": "PENDING", "message": "First STK prompt sent" }
```

**Sequential STK pushes for split payment:**
- One phone is prompted, the callback is processed (success or failure), then the next phone is prompted.
- If a payment fails, that phone is requeued at the end of the sequence after all others have been prompted.
- The tracking page shows progress: "2 of 3 payments received."

**Tips:**
After the amount is confirmed, the customer can add a tip:
- **Custom amount** (first option, default).
- **Presets:** KSh 300, 500, 1,000, 3,000 (restaurant can configure these).

**Tip distribution** (admin-configured):
- Admin sets percentages: e.g., kitchen 45%, business 10%, waitress/waiter 45%.
- System tracks per-waitress tip earnings and per-kitchen tip pool.
- Admin can view tip reports: per waitress, per order, total tips, and distribution breakdown.

**Verification:**
- The `public_token` ensures only the person with the tracking link can pay.
- The STK prompt goes to the customer's phone — they must enter their PIN.
- The payment goes to the business's till, not LeziTech's — the trust model is clear.

**What if the customer already paid at the table?**
- The waitress can process the payment from the POS as usual.
- The "Pay with M-Pesa" button disappears once the order is PAID.

**What if the order is split?**
- Each split bill has its own `public_token` and can be paid independently.

### 3.6 Order Verification

**The problem:** How does the restaurant ensure the person who picks up the food is the person who ordered and paid?

**Solution: Multi-layer verification.**

1. **Table-based service:** For dine-in, the waitress brings the food to the table. The table number on the order matches the physical table — no verification needed beyond "is this Table 5?"

2. **Name-based call-out:** If the customer entered their name (`customer_label`), the kitchen board displays it: "Order #0042 — Brian — READY". The waitress calls out the name.

3. **Takeaway verification:** If the order is TAKEAWAY (no table), the tracking page shows a **verification code** (4-digit number derived from the `public_token`). The customer shows this code at the counter, and the staff verifies it.

**Verification code generation:**
```java
// Derive a 4-digit code from the public_token
int code = Math.abs(publicToken.hashCode()) % 10000;
String verificationCode = String.format("%04d", code);
```

The tracking page shows: "Your pickup code: **4821** — show this at the counter."

### 3.7 Waitress Assignment (Load-Based Weighted Score)

Each order is automatically assigned to a waitress using a **weighted load score**. The system calculates the score for each available waitress and assigns the order to the one with the lowest score.

**Score calculation:**
The system uses the actual plate count and table count per waitress:
- Each order's lines (plates) are counted.
- Each active table served by the waitress adds to the score.
- The score = (number of active tables) + (total plates across all active orders).

**Example:**
- Waitress A: 2 tables, 8 plates total → score = 10
- Waitress B: 1 table, 3 plates total → score = 4 ← assigned
- Waitress C: 3 tables, 12 plates total → score = 15

**If a waitress manually entered the order, they keep it.** The auto-assignment only applies to self-orders placed by customers via QR code.

**Database change:**
```sql
ALTER TABLE orders ADD COLUMN assigned_waitress_id uuid REFERENCES staff(id);
```

**API addition:**
```
GET /api/waitresses/load-scores
Returns: [ { "staffId": "uuid", "name": "Jane", "tables": 2, "plates": 8, "score": 10 }, ... ]
```

### 3.8 ETR (Electronic Tax Receipt) via Email

**Pricing:** KSh 4 per ETR sent via email, billed monthly. This covers email sending and PDF storage costs.

**How it works:**
1. Customer opts in on the review page (checkbox: "I want to receive my ETR via email (KSh 4)").
2. After payment is confirmed, the system generates a PDF receipt and emails it immediately via Brevo.
3. If Brevo quota is exhausted, the ETR is queued and sent early the next morning.
4. The KSh 4 fee is added to the order total and collected with the payment.

**ETR notice on order confirmation:**
After the customer places the order, the confirmation screen shows: "You'll receive your ETR via email after payment."

**Consolidated tax reports:**
For company-specific ETRs, the system generates consolidated tax reports (daily, weekly, monthly, yearly, or custom date range). These are available in the admin dashboard as downloadable PDFs and CSVs. The revenue calculations in these reports are **editable by admins** in case of system calculation mistakes.

**Database changes:**
```sql
ALTER TABLE orders ADD COLUMN want_etr boolean DEFAULT false;
ALTER TABLE orders ADD COLUMN etr_sent boolean DEFAULT false;
ALTER TABLE orders ADD COLUMN etr_sent_at timestamptz;
ALTER TABLE orders ADD COLUMN etr_email varchar(255);

CREATE TABLE etr_queue (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id uuid NOT NULL REFERENCES orders(id),
  email varchar(255) NOT NULL,
  scheduled_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  error_message text
);
```

### 3.9 Tips

**Customer adds a tip during payment:**
- **Custom amount** (first option, default).
- **Presets:** KSh 300, 500, 1,000, 3,000 (restaurant can configure these).

**Tip distribution** (admin-configured):
- Admin sets percentages: e.g., kitchen 45%, business 10%, waitress/waiter 45%.
- System tracks per-waitress tip earnings and per-kitchen tip pool.
- Admin can view tip reports: per waitress, per order, total tips, and distribution breakdown.

**Database changes:**
```sql
ALTER TABLE orders ADD COLUMN tip_amount integer DEFAULT 0;

CREATE TABLE tip_distribution (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  kitchen_percent integer NOT NULL DEFAULT 45,
  business_percent integer NOT NULL DEFAULT 10,
  staff_percent integer NOT NULL DEFAULT 45,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tip_earnings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id uuid NOT NULL REFERENCES orders(id),
  staff_id uuid REFERENCES staff(id),
  tip_amount integer NOT NULL,
  kitchen_share integer NOT NULL,
  business_share integer NOT NULL,
  staff_share integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
```

### 3.10 Loyalty Program (Opt-In)

**Default:** Off. Restaurant enables it via a setup wizard.

**Setup wizard:**
1. Admin toggles loyalty on in settings.
2. Wizard guides through configuration: points earned, redemption value, expiry rules.
3. Fully custom rules — admin defines everything.

**How it works:**
- If the customer enters their phone number during ordering, the system links the order to their customer record.
- After payment, points are awarded based on the configured rules.
- Points can be redeemed for discounts on future orders.

**Database changes:**
```sql
CREATE TABLE loyalty_config (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  enabled boolean NOT NULL DEFAULT false,
  points_per_amount integer NOT NULL,  -- e.g., 1 point per KSh 100
  redemption_value integer NOT NULL,   -- e.g., 100 points = KSh 100 off
  expiry_days integer,                 -- null = no expiry
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE loyalty_points (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  customer_phone varchar(30) NOT NULL,
  points_balance integer NOT NULL DEFAULT 0,
  points_earned integer NOT NULL DEFAULT 0,
  points_redeemed integer NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

### 3.11 Menu Photos (Cloudflare R2)

**Storage:** Cloudflare R2 (~$0.015/GB/month, free egress). Images are uploaded by the restaurant admin and stored on R2.

**How it works:**
1. Admin uploads photos for menu items via the admin panel.
2. Images are compressed to thumbnails (max 500KB) before upload.
3. Photos are displayed prominently on the public menu page.

**Database change:**
```sql
ALTER TABLE items ADD COLUMN photo_url text;
```

**API addition:**
```
POST /api/items/{id}/photo
Body: Multipart form with image file.
Returns: { "photoUrl": "https://r2.cloudflare.com/..." }
```

### 3.12 Allergen Info

**Display:** Expandable section per item. Customer taps an "Allergens" button to see the allergen information.

**Database change:**
```sql
ALTER TABLE items ADD COLUMN allergens text[];  -- e.g., {'nuts', 'gluten', 'dairy'}
```

**Frontend:** Each menu item shows an "Allergens" button. Tapping it expands a list of allergens (e.g., "Contains: nuts, gluten").

### 3.13 Order Board Screens

**Admin-created named screens:**
- Admin creates screens with names/descriptions (e.g., "Main Hall", "Patio", "Bar Area").
- Each screen is assigned a set of tables.
- System suggests auto-creation based on the number of tables and screens.
- Each screen gets a unique URL: `https://lezitech.site/s/{slug}/board/{screenId}`.
- For now, no security key required (free access).

**Auto-creation suggestions:**
- System analyzes the number of tables and suggests an optimal number of screens.
- E.g., 20 tables → suggest 2 screens with 10 tables each.
- Admin approves or modifies the suggestion.

**Auto-scrolling:**
- Each screen auto-scrolls through the orders for its assigned tables.
- Font size and element size are adjusted based on the number of tables per screen for readability.

**Database changes:**
```sql
CREATE TABLE order_board_screens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  name varchar(100) NOT NULL,
  description text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE order_board_screen_tables (
  screen_id uuid NOT NULL REFERENCES order_board_screens(id),
  table_id uuid NOT NULL REFERENCES dining_tables(id),
  PRIMARY KEY (screen_id, table_id)
);
```

**API additions:**
```
GET /api/board-screens
Returns: [ { "id": "uuid", "name": "Main Hall", "tables": [...] }, ... ]

POST /api/board-screens
Body: { "name": "Patio", "tableIds": ["uuid1", "uuid2"] }

POST /api/board-screens/suggest
Returns: suggested screen configuration based on table count.

GET /api/public/board/{slug}/screen/{screenId}
Returns: orders for the tables assigned to this screen.
```

### 3.14 Support Access (Time-Limited)

**For emergency help when guiding the admin is difficult:**
- Admin requests support access from the platform.
- LeziTech team gets **full admin access** for a **5-hour window**.
- The admin can stop the access at any time.
- A countdown timer shows the remaining time.
- Access logs all actions taken by the support team.

**Database changes:**
```sql
CREATE TABLE support_access (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  requested_by uuid NOT NULL REFERENCES staff(id),
  granted_to varchar(100) NOT NULL,  -- LeziTech support agent ID
  granted_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  revoked_by uuid REFERENCES staff(id)
);

CREATE TABLE support_access_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  support_id uuid NOT NULL REFERENCES support_access(id),
  action text NOT NULL,
  details jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
```

### 3.15 Kitchen Rejection with Substitute

**If an item is out of stock:**
1. Kitchen can reject the order line and suggest a substitute item.
2. The customer is notified on their tracking page: "Nyama Choma is unavailable. Would you like Grilled Chicken instead?"
3. Customer can approve or decline.
4. If approved, the order line is updated with the substitute item.
5. If declined, a waitress follows up to explain and offer alternatives.

**Mark item unavailable:**
- Kitchen/waitress can mark a menu item as temporarily unavailable or "procurement on the way."
- Customers see the item as sold out on the menu.

**Database change:**
```sql
ALTER TABLE order_lines ADD COLUMN rejected boolean DEFAULT false;
ALTER TABLE order_lines ADD COLUMN substitute_item_id uuid REFERENCES items(id);
ALTER TABLE order_lines ADD COLUMN substitute_approved boolean;
```

### 3.16 Rating After Payment

**Appears on the payment confirmation page** (after payment is successful):
- "How was your experience?" — 5-star rating with optional comment.
- Rating is linked to the order and the restaurant.
- Admin can view ratings in the dashboard.

**Database change:**
```sql
ALTER TABLE orders ADD COLUMN rating integer;  -- 1-5
ALTER TABLE orders ADD COLUMN rating_comment text;
ALTER TABLE orders ADD COLUMN rated_at timestamptz;
```

### 3.17 The Waitress's Role

The waitress is not replaced — their role shifts from **order taker** to **service coordinator**:

**What the waitress sees on their tablet:**
- **Floor plan view** (already built): All tables with their status.
  - Green = order ready to serve (paid).
  - Yellow = order in kitchen (unpaid).
  - Grey = no order.
  - Red = order sent but not yet acknowledged by kitchen.
- **Table tap:** Shows the open order for that table, including:
  - Order lines with kitchen status.
  - Payment status (PAID / UNPAID).
  - Customer name if provided.
  - Assigned waitress name.
  - "Add items" button to add more lines from the till.
  - "Send to kitchen" button for lines added by the waitress.
- **My Orders view:** Shows all orders assigned to this waitress, grouped by status (in kitchen, ready to serve, paid).

**Waitress actions:**
- See which tables have orders ready to serve.
- See their assigned orders and load score.
- Add items to a customer's order from the tablet (e.g., the customer asks for extra water).
- Split the bill if the table wants separate checks.
- Process payment at the table if the customer prefers to pay later.
- Void an order if the customer leaves without ordering.
- Mark items as unavailable or "procurement on the way."
- Follow up on rejected orders where the customer declined the substitute.

---

## 4. Database Changes

### 4.1 New Columns on Existing Tables

```sql
-- Orders table
ALTER TABLE orders ADD COLUMN estimated_minutes integer;
ALTER TABLE orders ADD COLUMN estimated_ready_at timestamptz;
ALTER TABLE orders ADD COLUMN customer_phone varchar(30);
ALTER TABLE orders ADD COLUMN verification_code varchar(4);
ALTER TABLE orders ADD COLUMN assigned_waitress_id uuid REFERENCES staff(id);
ALTER TABLE orders ADD COLUMN batch_id uuid;
ALTER TABLE orders ADD COLUMN want_etr boolean DEFAULT false;
ALTER TABLE orders ADD COLUMN etr_sent boolean DEFAULT false;
ALTER TABLE orders ADD COLUMN etr_sent_at timestamptz;
ALTER TABLE orders ADD COLUMN etr_email varchar(255);
ALTER TABLE orders ADD COLUMN tip_amount integer DEFAULT 0;
ALTER TABLE orders ADD COLUMN rating integer;  -- 1-5
ALTER TABLE orders ADD COLUMN rating_comment text;
ALTER TABLE orders ADD COLUMN rated_at timestamptz;

-- Order lines table
ALTER TABLE order_lines ADD COLUMN rejected boolean DEFAULT false;
ALTER TABLE order_lines ADD COLUMN substitute_item_id uuid REFERENCES items(id);
ALTER TABLE order_lines ADD COLUMN substitute_approved boolean;

-- Items table
ALTER TABLE items ADD COLUMN photo_url text;
ALTER TABLE items ADD COLUMN allergens text[];  -- e.g., {'nuts', 'gluten', 'dairy'}
```

### 4.2 New Tables

```sql
-- Item preparation times (for estimated time suggestions)
CREATE TABLE item_prep_times (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  item_id uuid NOT NULL REFERENCES items(id),
  avg_minutes integer NOT NULL,
  sample_count integer NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- ETR queue (for when Brevo quota is exhausted)
CREATE TABLE etr_queue (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id uuid NOT NULL REFERENCES orders(id),
  email varchar(255) NOT NULL,
  scheduled_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  error_message text
);

-- Tip distribution config (per tenant)
CREATE TABLE tip_distribution (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  kitchen_percent integer NOT NULL DEFAULT 45,
  business_percent integer NOT NULL DEFAULT 10,
  staff_percent integer NOT NULL DEFAULT 45,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Tip earnings (per order)
CREATE TABLE tip_earnings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id uuid NOT NULL REFERENCES orders(id),
  staff_id uuid REFERENCES staff(id),
  tip_amount integer NOT NULL,
  kitchen_share integer NOT NULL,
  business_share integer NOT NULL,
  staff_share integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Loyalty config (per tenant, opt-in)
CREATE TABLE loyalty_config (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  enabled boolean NOT NULL DEFAULT false,
  points_per_amount integer NOT NULL,
  redemption_value integer NOT NULL,
  expiry_days integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Loyalty points (per customer phone)
CREATE TABLE loyalty_points (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  customer_phone varchar(30) NOT NULL,
  points_balance integer NOT NULL DEFAULT 0,
  points_earned integer NOT NULL DEFAULT 0,
  points_redeemed integer NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Order board screens (admin-created named screens)
CREATE TABLE order_board_screens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  name varchar(100) NOT NULL,
  description text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE order_board_screen_tables (
  screen_id uuid NOT NULL REFERENCES order_board_screens(id),
  table_id uuid NOT NULL REFERENCES dining_tables(id),
  PRIMARY KEY (screen_id, table_id)
);

-- Support access (time-limited full admin access)
CREATE TABLE support_access (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  requested_by uuid NOT NULL REFERENCES staff(id),
  granted_to varchar(100) NOT NULL,
  granted_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  revoked_by uuid REFERENCES staff(id)
);

CREATE TABLE support_access_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  support_id uuid NOT NULL REFERENCES support_access(id),
  action text NOT NULL,
  details jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
```

---

## 5. API Changes Summary

### 5.1 New Endpoints

| Endpoint | Method | Purpose | Auth |
|----------|--------|---------|------|
| `/api/tables/{id}/qr-url` | GET | Get QR code URL for a table | Staff |
| `/api/tables/qr-cards-pdf` | GET | Download branded QR cards PDF | Admin |
| `/api/public/order` | POST | Customer places order from table | None |
| `/api/public/batch-order` | POST | Customer places batch order across tables | None |
| `/api/public/order/{token}/pay` | POST | Customer pays via M-Pesa STK | None |
| `/api/public/order/{token}/pay-split` | POST | Customer splits payment across phones | None |
| `/api/orders/{id}/estimate` | PATCH | Kitchen sets estimated time | Staff |
| `/api/waitresses/load-scores` | GET | Get waitress load scores for assignment | Staff |
| `/api/items/{id}/photo` | POST | Upload menu item photo | Admin |
| `/api/board-screens` | GET | List order board screens | Staff |
| `/api/board-screens` | POST | Create order board screen | Admin |
| `/api/board-screens/suggest` | POST | Suggest screen configuration | Admin |
| `/api/public/board/{slug}/screen/{screenId}` | GET | Orders for a specific board screen | None |
| `/api/support-access/request` | POST | Request support access | Admin |
| `/api/support-access/revoke` | POST | Revoke support access | Admin |
| `/api/tips/distribution` | GET/PUT | Get/set tip distribution config | Admin |
| `/api/tips/report` | GET | Get tip earnings report | Admin |
| `/api/loyalty/config` | GET/PUT | Get/set loyalty config | Admin |
| `/api/etr/report` | GET | Get consolidated tax report (PDF/CSV) | Admin |

### 5.2 Existing Endpoints (Used by This Workflow)

| Endpoint | Method | Purpose | Auth |
|----------|--------|---------|------|
| `/api/public/menu/{slug}` | GET | Menu browsing with photos and allergens | None |
| `/api/public/track/{slug}/{token}` | GET | Order tracking with estimated time | None |
| `/api/public/board/{slug}?k={key}` | GET | Kitchen display board | None |
| `/api/floors` | GET | Floor plan with tables | Staff |
| `/api/kitchen?station=kitchen` | GET | Kitchen display by station | Staff |
| `/api/kitchen/lines/{id}` | POST | Advance kitchen line status | Staff |
| `/api/kitchen/orders/{orderId}/bump` | POST | Bump entire ticket one step | Staff |
| `/api/orders/{id}/split` | POST | Split bill | Staff |
| `/api/orders/{id}/send` | POST | Send to kitchen | Staff |

---

## 6. Frontend Pages

### 6.1 Table Menu Page (New)
**URL:** `/s/{slug}/table/{tableId}`

- Shows the restaurant-branded menu with **photos** and **allergen info**.
- "Table {label}" badge at the top.
- **Group Order mode:** Toggle for batch ordering across multiple tables.
- Cart with add-to-cart, quantity, add-ons, special instructions.
- Optional name and phone fields before placing the order.
- **Review page** before sending to kitchen (no modification after).
- ETR checkbox on review page.
- After ordering: confirmation with tracking link, "Track My Order" button, and ETR notice.

### 6.2 Order Tracking Page (Enhanced)
**URL:** `/s/{slug}/track/{token}`

- Already exists, needs enhancements:
  - Add estimated time display with countdown timer.
  - Add "Pay with M-Pesa" button when order is READY.
  - Add "Split Payment" button for batch orders.
  - Add tip input (custom amount + configurable presets).
  - Add verification code for takeaway orders.
  - Add progress bar with stages.
  - Auto-refresh every 10 seconds.
  - **Rating prompt** after payment confirmation (5-star + optional comment).
  - **Kitchen rejection notification:** If an item is rejected with a substitute, show the suggestion and approve/decline buttons.
  - **Split payment progress:** "2 of 3 payments received."

### 6.3 Floor Plan View (Enhanced)
**URL:** Already exists in the frontend.

- Add color-coded table status (green/yellow/grey/red).
- Add payment status indicator on each table.
- Add assigned waitress name on each table.
- Add "Download QR Cards" button (generates branded PDF).
- **My Orders view:** Shows all orders assigned to the current waitress.

### 6.4 Kitchen Display (Enhanced)
**URL:** Already exists in the frontend.

- Add estimated time input field (system suggests based on history).
- Add customer name display.
- Add table number prominently.
- Add assigned waitress name.
- **Mark item unavailable:** Button to mark items as sold out or "procurement on the way."
- **Reject with substitute:** If an item is out of stock, suggest a substitute and notify the customer.

### 6.5 Order Board Screens (New)
**URL:** `/s/{slug}/board/{screenId}`

- Admin-created named screens (e.g., "Main Hall", "Patio").
- Each screen shows orders for its assigned tables.
- Auto-scrolling through orders.
- Font size and element size adjusted based on the number of tables per screen.
- **Auto-creation suggestions:** System suggests optimal screen configuration based on table count.

### 6.6 Admin Panel Additions

- **QR Cards:** Download branded QR cards PDF from the floor plan view.
- **Tip Distribution:** Configure tip percentages (kitchen, business, staff).
- **Tip Reports:** View per-waitress, per-order, and total tip earnings.
- **Loyalty Setup Wizard:** Configure loyalty program (opt-in, fully custom rules).
- **ETR Reports:** Download consolidated tax reports (PDF/CSV) for daily/weekly/monthly/yearly/custom ranges. Revenue calculations are editable.
- **Menu Photos:** Upload photos for menu items (stored on Cloudflare R2).
- **Allergen Info:** Add allergen information per menu item.
- **Order Board Screens:** Create, edit, and manage named screens with table assignments.
- **Support Access:** Request and revoke time-limited support access.
- **Ratings:** View customer ratings and comments.

---

## 7. How This Fits Into BizNez

### 7.1 Sector-Aware Activation

This workflow is **restaurant-specific**. It activates automatically when:
- The tenant's `sector = 'hospitality'` and `sub_sector` is restaurant, café, bar, fast food, or bakery.
- The tenant has at least one `dining_table` configured.

For other sectors, the table-ordering features are hidden. A hardware shop doesn't need QR codes on tables.

**AI model awareness:** The AI assistant (when built) will check the sector before suggesting table-ordering features. If a non-restaurant tenant tries to access `/api/tables`, the AI will note that this feature is designed for hospitality businesses.

### 7.2 It's Part of the POS Module

The table-ordering workflow is not a separate product — it's a **mode of the POS**:

- **Counter POS:** Cashier rings up items at the till (existing flow).
- **Table POS:** Customer orders from their phone via QR code (new flow).
- **Hybrid:** Waitress takes the order on the tablet, customer tracks and pays from their phone.

All three modes share the same order, payment, and kitchen infrastructure. The difference is who initiates the order and how the customer interacts with it.

### 7.3 Marketing Angle

For landing pages and sales conversations, this is positioned as:

**"Self-Ordering for Restaurants"** — a feature that:
- Reduces wait times for customers.
- Frees up waitstaff for service, not order-taking.
- Reduces order errors (customer enters their own order).
- Speeds up table turnover.
- Works with M-Pesa — no card machine needed.
- Supports batch ordering for large groups (school groups, events).
- Provides ETR via email for KRA compliance.
- Includes tips, loyalty, and ratings — all in one system.

**Pricing:** Included in all plans for restaurant tenants. ETR is KSh 4 per receipt, billed monthly.

---

## 8. Implementation Phases

### Phase 1: Foundation (Weeks 1–2)
- QR code generation per table and branded QR card PDF download.
- `/api/tables/{id}/qr-url` and `/api/tables/qr-cards-pdf` endpoints.
- The `/s/{slug}/table/{tableId}` page loads the public menu with the table context.
- Customer self-ordering: `POST /api/public/order` endpoint.
- Order review page (no modification after placement).
- Database migrations for new columns and tables.

### Phase 2: Waitress Assignment + Estimated Time (Week 3)
- Load-based weighted score waitress assignment.
- Historical preparation time tracking (`item_prep_times` table).
- Hybrid estimated time: system suggests, kitchen overrides.
- `/api/waitresses/load-scores` endpoint.

### Phase 3: Self-Payment + Split Payment (Week 4)
- `POST /api/public/order/{token}/pay` endpoint.
- M-Pesa STK push from the tracking page.
- Split payment: `POST /api/public/order/{token}/pay-split`.
- Sequential STK pushes with requeue on failure.
- Tips: custom amount + configurable presets.
- Tip distribution config and earnings tracking.

### Phase 4: Batch Ordering + Order Board Screens (Week 5)
- `POST /api/public/batch-order` endpoint.
- Group order mode on the menu page.
- Order board screens: admin-created named screens with auto-creation suggestions.
- Auto-scrolling order boards.

### Phase 5: ETR + Rating + Loyalty (Week 6)
- ETR checkbox on review page, KSh 4 fee.
- ETR PDF generation and email via Brevo with queue for quota exhaustion.
- Consolidated tax reports (PDF/CSV, editable revenue).
- Rating prompt after payment confirmation.
- Loyalty setup wizard and points tracking.

### Phase 6: Menu Photos + Allergens + Kitchen Features (Week 7)
- Menu photo upload to Cloudflare R2.
- Allergen info per item (expandable).
- Kitchen rejection with substitute option.
- Mark item unavailable / "procurement on the way."

### Phase 7: Support Access + Polish (Week 8)
- Time-limited support access (5-hour window, full admin, revocable).
- Support access logging.
- Floor plan color coding and waitress assignment display.
- Real-time tracking updates (polling or SSE).
- Verification code for takeaway orders.

---

## 9. Edge Cases & Security

### 9.1 Someone Places a Fake Order

**Risk:** A random person scans a table's QR code and places a fake order to disrupt the kitchen.

**Mitigation:**
- Rate limiting: max 5 orders per table per hour.
- The kitchen display shows the table number — staff can reject obvious fake orders.
- The order doesn't cost the restaurant anything until the customer pays.
- The waitress can void an order from the floor plan view.
- **Optional deposit:** Restaurant can enable a small deposit (e.g., KSh 50) via M-Pesa before the order is sent to the kitchen.

### 9.2 Customer Orders but Leaves Without Paying

**Risk:** The customer places an order, the kitchen prepares it, but the customer leaves.

**Mitigation:**
- The floor plan view shows unpaid orders prominently.
- The assigned waitress sees the unpaid order and can follow up.
- If the customer doesn't return, the waitress voids the order and logs the waste.
- **Optional deposit:** If enabled, the deposit is forfeited.

### 9.3 Customer Pays but Wrong Order is Served

**Risk:** Two orders at adjacent tables are confused.

**Mitigation:**
- Table number is on every screen: kitchen display, waitress tablet, customer tracking.
- Name-based call-out if the customer provided a name.
- Verification code for takeaway orders.
- Assigned waitress knows which table their order belongs to.

### 9.4 M-Pesa Payment Fails

**Risk:** The STK prompt times out or the customer cancels.

**Mitigation:**
- The tracking page shows "Payment failed — try again or pay at the table."
- The customer can retry the STK push.
- The waitress can process the payment at the table as a fallback.
- For split payment, failed phones are requeued at the end of the sequence.

### 9.5 Internet Goes Down

**Risk:** The customer's phone loses internet while tracking.

**Mitigation:**
- The order board (TV screen) still shows the order status — it's a local display.
- When internet returns, the tracking page refreshes.
- The kitchen display works offline (staff actions are queued).

### 9.6 Multiple Orders on the Same Table

**Risk:** A group splits into two separate orders (e.g., different courses).

**Mitigation:**
- The "Add More Items" button adds to the existing open order.
- If the group wants separate bills, the waitress uses the split bill feature (already built).
- Each split bill has its own tracking link and payment.

### 9.7 Batch Order Payment Partial Failure

**Risk:** In a split payment across multiple phones, some payments succeed and some fail.

**Mitigation:**
- Failed phones are requeued at the end of the sequence.
- The tracking page shows progress: "2 of 3 payments received."
- The order is not marked PAID until all portions are paid.
- The waitress can process the remaining amount at the table as a fallback.

### 9.8 Kitchen Rejects Item — Customer Declines Substitute

**Risk:** The kitchen suggests a substitute but the customer doesn't want it.

**Mitigation:**
- The assigned waitress is notified to follow up with the customer.
- The waitress can offer alternatives or adjust the order manually.
- If the entire order is rejected, the waitress voids the order and logs the reason.

### 9.9 ETR Email Fails (Brevo Quota)

**Risk:** Brevo email quota is exhausted, so the ETR can't be sent immediately.

**Mitigation:**
- The ETR is queued in `etr_queue` table.
- A scheduled job sends queued ETRs early the next morning.
- The customer sees "Your ETR will be sent via email shortly" on the confirmation page.

### 9.10 Support Access Abuse

**Risk:** A LeziTech support agent misuses the time-limited access.

**Mitigation:**
- Access is limited to 5 hours maximum.
- The admin can revoke access at any time.
- All actions taken by the support team are logged in `support_access_log`.
- LeziTech can audit the logs for compliance.

### 9.11 Loyalty Points Exploitation

**Risk:** A customer creates multiple phone numbers to earn points repeatedly.

**Mitigation:**
- Loyalty is opt-in and configured by the restaurant admin.
- Admin can set expiry rules and monitor point balances.
- The system tracks points earned and redeemed per phone number for audit.

---

## 10. Comparison with Competitors

| Feature | BizNez | Odoo POS | Square for Restaurants | Toast |
|---------|----------|----------|----------------------|-------|
| QR table ordering | ✓ Built-in | Add-on module | ✓ | ✓ |
| M-Pesa self-payment | ✓ Native | Plugin | ✗ | ✗ |
| Split payment (multiple phones) | ✓ Native | ✗ | ✗ | ✗ |
| Kitchen display | ✓ Built-in | ✓ | ✓ | ✓ |
| Order tracking | ✓ Customer-facing | ✗ | Limited | ✓ |
| Estimated time (hybrid) | ✓ Planned | ✓ | ✓ | ✓ |
| Split bill | ✓ Built-in | ✓ | ✓ | ✓ |
| Offline mode | ✓ Built-in | Limited | Limited | Limited |
| Batch ordering (group) | ✓ Planned | ✗ | ✗ | ✗ |
| ETR via email | ✓ KSh 4/receipt | Plugin | ✗ | ✗ |
| Tips with distribution | ✓ Planned | ✓ | ✓ | ✓ |
| Loyalty program | ✓ Opt-in | ✓ | ✓ | ✓ |
| Menu photos | ✓ Cloudflare R2 | ✓ | ✓ | ✓ |
| Allergen info | ✓ Expandable | ✓ | ✓ | ✓ |
| Cost | KSh 2,650/mo | $70+/mo + hosting | $100+/mo | $200+/mo |

**Key differentiators:**
- **M-Pesa self-payment** is native. No other restaurant POS in Kenya offers this.
- **Split payment across multiple phones** — unique to BizNez.
- **Batch ordering for large groups** — designed for Kenyan school groups and events.
- **ETR via email** — KRA compliance built in.
- **The customer pays directly from their tracking page** without a card machine or waiter intervention.

---

## 11. Summary

The restaurant table-ordering workflow is **80% already built**. The existing platform has:
- Floor plan with tables.
- Kitchen display with status flow.
- Public menu browsing.
- Customer-facing order tracking.
- M-Pesa STK payment infrastructure.
- Split bill, send to kitchen, and all the core POS features.

What's needed is the **connecting layer** (19 new features):
1. QR code generation per table with branded card download.
2. Customer self-ordering from the public menu.
3. Order review page (no modification after placement).
4. Waitress auto-assignment via load-based weighted scoring.
5. Batch ordering for large groups across multiple tables.
6. Hybrid estimated time (system suggests, kitchen overrides).
7. Self-payment from the tracking page.
8. Split payment across multiple phones (sequential STK pushes).
9. Tips with configurable presets and admin-configured distribution.
10. Rating after payment confirmation.
11. ETR via email (KSh 4 per receipt, billed monthly).
12. Consolidated tax reports (PDF/CSV, editable revenue).
13. Loyalty program (opt-in, setup wizard, fully custom rules).
14. Menu photos (Cloudflare R2 storage).
15. Allergen info (expandable per item).
16. Order board screens (admin-created, auto-scrolling).
17. Support access (time-limited, full admin, revocable).
18. Kitchen rejection with substitute option.
19. Order verification for takeaway.

This is an **8-week build** that transforms the restaurant POS from a waiter-assisted system into a self-service workflow that reduces wait times, frees up staff, speeds up table turnover, and handles large groups — all while keeping the M-Pesa trust model intact.

The feature is contained within BizNez as a **sector-specific mode of the POS**. It activates automatically for restaurant tenants and is invisible to other sectors. This is exactly what "personalised per business experience" means.

---

## 12. Design Decisions Summary

All design decisions in this document were discussed and confirmed with the product owner. Here's a quick reference:

| Decision | Choice |
|----------|--------|
| QR card design | BizNez-generated template, restaurant-owned, downloadable PDF |
| Deposit to order | Optional (restaurant can enable) |
| Tip presets | Restaurant-configurable, custom entry first, defaults: 300/500/1000/3000 |
| Rating timing | After payment confirmation |
| Loyalty program | Opt-in, setup wizard, fully custom rules |
| Estimated time | Hybrid: system suggests based on history, kitchen overrides |
| Menu language | English only for now |
| Allergen info | Expandable button per item |
| Menu photos | Yes, Cloudflare R2 storage |
| Table capacity | No enforcement, flexible seating |
| Support access | Full admin, 5-hour window, revocable by admin |
| Waitress assignment | Load-based weighted score (tables + plates) |
| Batch order payment | Split allowed: equal, custom amount, or percentage |
| Batch order tracking | Per-table tracking under one batch ID |
| Tip distribution | Admin-configured percentages (kitchen/business/staff) |
| Image storage | Cloudflare R2 from day one |
| Order board screens | Admin-created named screens with auto-creation suggestions |
| ETR timing | Immediately after payment, retry next morning if Brevo quota exhausted |
| ETR reports | PDF and CSV, editable revenue calculations |
| Order modification | No modification after placement, review page before sending |
| Kitchen rejection | Reject with substitute option, waitress follows up if declined |
| Split payment flow | Sequential STK pushes, failed phones requeued at end |