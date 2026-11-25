# Busynez Supply phase 1: rounds and delivery proof (API contract)

Backend worktree `J:\backend\wt-supply`, web worktree `J:\frontend\wt-supply`, both on branch `supply` cut from origin/launch. Migration **V80** (V79 is taken by the unpushed `oversight` branch). Nothing is pushed until the owner's V78/V79 deploy is sorted.

Rule: every handover has two names. Store loads (name 1), driver accepts on his own phone (name 2). Driver delivers, the client's clerk confirms (driver cannot edit it).

## Statuses
Run: LOADING -> OUT (driver accepted) -> BACK. Stop: PENDING -> EMAILED -> CONFIRMED (all received) | PART (short or sent back).

## Staff endpoints (JWT, module `rounds`)
| Call | Who | What |
|---|---|---|
| GET /api/rounds?date=YYYY-MM-DD | OWNER, MANAGER, STOREKEEPER, ACCOUNTANT | `[{id, runDate, vehicle, driverUserId, driverName, status, stopCount, confirmedCount}]` |
| GET /api/rounds/orders-ready | OWNER, MANAGER | OPEN orders with a customer, not on a stop of a run that is not BACK: `[{orderId, number, customerName, customerEmail, total, lines:[{itemId,name,qty}]}]` |
| POST /api/rounds | OWNER, MANAGER | body `{runDate, vehicle, driverUserId, orderIds:[]}`; driver must be an active staff user with role RIDER; each order OPEN with a customer. Returns detail |
| GET /api/rounds/{id} | OWNER, MANAGER, STOREKEEPER, ACCOUNTANT, or the run's driver | detail (below) |
| POST /api/rounds/{id}/load | OWNER, MANAGER, STOREKEEPER | body `{lines:[{itemId, qtyLoaded}]}`; only while LOADING; records loadedBy name + time |
| POST /api/rounds/{id}/accept | only the run's driver (not even the owner) | needs a saved load; sets acceptedBy/acceptedAt, status OUT, load locked |
| GET /api/rounds/mine | RIDER (any signed-in user) | runs where driver = me and status != BACK, each as detail |
| POST /api/rounds/stops/{stopId}/arrived | run's driver | body `{email?}`; run must be OUT; emails the clerk a link + 4 digit code; a resend makes a new link and code (old ones stop working); max 3 sends; status EMAILED |
| POST /api/rounds/stops/{stopId}/code | run's driver | body `{code, cashCollected}`; right code confirms every line received in full, confirmedVia CODE; 5 wrong tries lock the code (resend needed) |
| POST /api/rounds/stops/{stopId}/cash | run's driver | body `{cashCollected}` after the clerk confirmed by link |
| POST /api/rounds/{id}/back | run's driver, OWNER, MANAGER | status BACK |

Detail shape:
```
{ id, runDate, vehicle, driverUserId, driverName, status,
  loadedByName, loadedAt, acceptedByName, acceptedAt,
  load: [{itemId, name, qtyPlanned, qtyLoaded}],
  stops: [{ id, sort, noteNo, orderId, orderNumber, customerName, email, status,
            emailedAt, sends, receivedByName, confirmedAt, confirmedVia, cashCollected,
            lines: [{lineId, itemId, name, qtySent, qtyReceived, qtySentBack, reason}] }],
  totals: [{itemId, name, loaded, received, sentBack, missing}] }   // missing = loaded - received - sentBack (confirmed stops only count once all stops confirmed; before that show as null)
```
Errors: 400/409 with `{message}` in plain words (house style: no "owe/owed/due", no dashes).

## Public endpoints (no login)
- GET /api/public/delivery/{token} -> `{supplierName, customerName, noteNo, orderNumber, driverName, vehicle, emailedAt, status, lines:[{lineId, name, qtySent}], confirmed: null | {receivedByName, confirmedAt, lines:[{lineId, qtyReceived, qtySentBack, reason}]}}`. 404 when unknown or expired (3 days) or replaced.
- POST /api/public/delivery/{token} body `{receivedByName, signature: "data:image/png;base64,...", agreed: true, lines:[{lineId, qtyReceived, qtySentBack, reason}]}`. Once only (409 after). Each line: 0 <= received, 0 <= sentBack, received + sentBack <= sent; reason required when sentBack > 0. Signature required, <= 150 KB.

## Notes
- Delivery note number `DN-<n>` per tenant.
- On confirm (link or code): order line qty set to what was received, through the existing order path that recalculates totals, so the invoice follows what was confirmed. Activity rows: `DELIVERY_SHORT` CONCERN (names the driver, item, amount), `DELIVERY_SENT_BACK` NOTICE, `LOAD_ACCEPTED` INFO.
