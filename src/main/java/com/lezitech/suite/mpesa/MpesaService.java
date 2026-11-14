package com.lezitech.suite.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.integrations.IntegrationService;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.settings.PlanActivation;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lipa na M-Pesa for the till and for LeziTech subscriptions.
 *
 * Till prompts go through the business's own account (Settings, Payments): its Daraja app for its till or
 * paybill, or its PayHero account. Plan payments go through LeziTech's own account (see MpesaConfig).
 *
 * Flow: the till asks for a prompt (STK push) -> the customer enters their PIN -> the provider posts the
 * result to a secret callback URL -> we check it against the request we sent and only then record the
 * payment on the order, once. Daraja's callback carries the result; PayHero's isn't signed, so we ask
 * PayHero for the status ourselves (also while the till is polling, in case the callback never arrives).
 * Direct till payments (C2B) are stored and matched to an open order by its number when the customer
 * typed it as the account.
 */
@Service
public class MpesaService {

    private static final Logger log = LoggerFactory.getLogger(MpesaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MpesaConfig cfg;
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final PlanActivation plans;
    private final TransactionTemplate tx;
    private final IntegrationService integrations;
    private final DarajaGateway daraja;
    private final PayHeroGateway payHero;
    private final SimulatedGateway simulator;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.property.LeaseLedger leases;

    /** A prompt for a sale from the diner's phone has its final answer (paid, failed, or paid but not applied). */
    public record OrderPromptSettled(UUID tenant, UUID orderId, UUID requestId, UUID splitId, BigDecimal tip, String outcome, UUID batchId) {
    }

    public MpesaService(MpesaConfig cfg, JdbcTemplate jdbc, OrderService orders, PlanActivation plans, PlatformTransactionManager txm,
                        IntegrationService integrations, DarajaGateway daraja, PayHeroGateway payHero) {
        this.cfg = cfg;
        this.jdbc = jdbc;
        this.orders = orders;
        this.plans = plans;
        this.tx = new TransactionTemplate(txm);
        this.integrations = integrations;
        this.daraja = daraja;
        this.payHero = payHero;
        this.simulator = new SimulatedGateway(cfg.simulateDelayMs, this::deliverSimulated);
    }

    /** What the till needs to know: can it send prompts for this business, and through which provider. */
    public Map<String, Object> publicConfig() {
        Optional<PayAccount> acct = cfg.enabled() ? integrations.tillAccount(TenantContext.tenantId()) : Optional.empty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.simulated() || acct.isPresent());
        m.put("mode", cfg.mode());
        m.put("simulated", cfg.simulated());
        m.put("provider", acct.map(PayAccount::provider).orElse(cfg.simulated() ? "SIMULATED" : null));
        return m;
    }

    // ---------------- STK push ----------------

    /** Send a "pay with M-Pesa" prompt for what's still due on an open sale (or part of it). */
    public Map<String, Object> startOrderPayment(UUID orderId, String phoneRaw, BigDecimal amountOrNull) {
        requireEnabled();
        String phone = normalizePhone(phoneRaw);
        Map<String, Object> o = jdbc.queryForMap("SELECT number, status, kind, total, paid FROM orders WHERE id = ? AND tenant_id = ?",
                orderId, TenantContext.tenantId());
        if (!"OPEN".equals(o.get("status"))) {
            throw ApiException.conflict("This sale is already closed");
        }
        if ("QUOTE".equals(o.get("kind"))) {
            throw ApiException.bad("Convert the quotation to a sale before taking payment");
        }
        BigDecimal due = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid"));
        BigDecimal amount = amountOrNull == null ? due : amountOrNull;
        if (amount.signum() <= 0) {
            throw ApiException.bad("Nothing left to pay on this sale");
        }
        if (amount.compareTo(due) > 0) {
            throw ApiException.bad("That's more than the KES " + due.toPlainString() + " still due");
        }
        Optional<PayAccount> acct = integrations.tillAccount(TenantContext.tenantId());
        if (acct.isEmpty() && !cfg.simulated()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_SETUP",
                    "M-Pesa prompts aren't set up for this business yet. The owner can add the till or paybill under Settings, Payments, "
                            + "or ask LeziTech to do it. For now, take the payment on your phone and type the M-Pesa code.");
        }
        UUID id = insertRequest("ORDER", orderId, null, phone, amount);
        return push(id, acct.orElse(null), phone, amount, "Sale " + o.get("number"), "Payment");
    }

    /**
     * A diner pays from their phone (or one share of a split): {@code part} of what's due, plus an optional tip on top.
     * The prompt goes to the business's own till or paybill, exactly like the till's prompts.
     */
    public Map<String, Object> startTablePayment(UUID orderId, String phoneRaw, BigDecimal part, BigDecimal tip, UUID splitId) {
        return startTablePayment(orderId, phoneRaw, part, tip, splitId, null);
    }

    /**
     * As above; with {@code batchId} the prompt is for a group order's combined bill: {@code orderId} is the group's
     * lead order (where the request is filed), and what's due is the sum over the group's open orders.
     */
    public Map<String, Object> startTablePayment(UUID orderId, String phoneRaw, BigDecimal part, BigDecimal tip, UUID splitId, UUID batchId) {
        requireEnabled();
        String phone = normalizePhone(phoneRaw);
        Map<String, Object> o = jdbc.queryForMap("SELECT number, status, total, paid FROM orders WHERE id = ? AND tenant_id = ?",
                orderId, TenantContext.tenantId());
        BigDecimal due;
        if (batchId == null) {
            if (!"OPEN".equals(o.get("status"))) {
                throw ApiException.conflict("This order is already paid");
            }
            due = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid"));
        } else {
            due = groupDue(batchId);
            if (due.signum() <= 0) {
                throw ApiException.conflict("This group's bill is already paid");
            }
        }
        BigDecimal t = tip == null ? BigDecimal.ZERO : tip;
        if (part.signum() <= 0 || part.compareTo(due) > 0 || t.signum() < 0) {
            throw ApiException.bad("Nothing left to pay on this order");
        }
        Optional<PayAccount> acct = integrations.tillAccount(TenantContext.tenantId());
        if (acct.isEmpty() && !cfg.simulated()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_SETUP",
                    "Paying from your phone isn't switched on here yet. Please ask your waiter to help you pay.");
        }
        BigDecimal amount = part.add(t);
        UUID id = insertRequest("ORDER", orderId, null, phone, amount);
        jdbc.update("UPDATE mpesa_requests SET tip = ?, split_id = ?, batch_id = ? WHERE id = ?", t, splitId, batchId, id);
        return push(id, acct.orElse(null), phone, amount, (batchId == null ? "Order " : "Group ") + o.get("number"), "Payment");
    }

    /**
     * A landlord asks a tenant to pay their rent from their phone: {@code amount} defaults to everything they owe.
     * The money is put on the lease's invoices, the one due first first; anything extra is kept as credit.
     */
    public Map<String, Object> startLeasePayment(UUID leaseId, String phoneRaw, BigDecimal amountOrNull) {
        requireEnabled();
        // leases sit behind row-level security: read them inside the workspace's transaction.
        Map<String, Object> l = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT l.status, c.phone, sp.name AS unit FROM leases l JOIN customers c ON c.id = l.customer_id JOIN spaces sp ON sp.id = l.space_id
                    WHERE l.id = ? AND l.tenant_id = ?""", leaseId, TenantContext.tenantId());
            if (rows.isEmpty()) {
                throw ApiException.notFound("Lease");
            }
            Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
            row.put("owed", leases.balance(leaseId));
            return row;
        });
        String phone = normalizePhone(phoneRaw == null || phoneRaw.isBlank() ? (String) l.get("phone") : phoneRaw);
        BigDecimal owed = (BigDecimal) l.get("owed");
        BigDecimal amount = amountOrNull == null ? owed : amountOrNull;
        if (amount.signum() <= 0) {
            throw ApiException.bad("Nothing is owed on this lease. Enter an amount to take a payment in advance.");
        }
        Optional<PayAccount> acct = integrations.tillAccount(TenantContext.tenantId());
        if (acct.isEmpty() && !cfg.simulated()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_SETUP",
                    "M-Pesa prompts aren't set up for this business yet. The owner can add the paybill under Settings, Payments, "
                            + "or ask LeziTech to do it. For now, record payments by hand with the M-Pesa code.");
        }
        BigDecimal whole = amount.setScale(0, RoundingMode.CEILING);
        UUID id = insertRequest("LEASE", null, null, phone, whole);
        jdbc.update("UPDATE mpesa_requests SET lease_id = ? WHERE id = ?", leaseId, id);
        String unit = String.valueOf(l.get("unit"));
        return push(id, acct.orElse(null), phone, whole, unit.length() > 12 ? unit.substring(0, 12) : unit, "Rent");
    }

    /**
     * A food court order: one prompt for the whole basket (several stalls), to the mall's own till or paybill. When it's
     * paid the order goes to each stall's screen.
     */
    public Map<String, Object> startFoodCourtPayment(UUID orderId, String phoneRaw) {
        requireEnabled();
        Map<String, Object> o = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT number, total, status FROM fc_orders WHERE id = ? AND tenant_id = ?",
                    orderId, TenantContext.tenantId());
            if (rows.isEmpty()) {
                throw ApiException.notFound("Order");
            }
            return rows.get(0);
        });
        if (!"WAITING_PAYMENT".equals(o.get("status"))) {
            throw ApiException.conflict("This order is already paid");
        }
        String phone = normalizePhone(phoneRaw);
        Optional<PayAccount> acct = integrations.tillAccount(TenantContext.tenantId());
        if (acct.isEmpty() && !cfg.simulated()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_SETUP",
                    "Paying by phone isn't switched on here yet. Please pay at the food court desk.");
        }
        BigDecimal amount = ((BigDecimal) o.get("total")).setScale(0, RoundingMode.CEILING);
        UUID id = insertRequest("FOODCOURT", null, null, phone, amount);
        jdbc.update("UPDATE mpesa_requests SET fc_order_id = ? WHERE id = ?", orderId, id);
        return push(id, acct.orElse(null), phone, amount, "Order " + o.get("number"), "Food court");
    }

    /** What's still due over a group order's open bills. */
    public BigDecimal groupDue(UUID batchId) {
        BigDecimal due = jdbc.queryForObject("""
                SELECT coalesce(sum(greatest(total - paid, 0)), 0) FROM orders
                WHERE tenant_id = ? AND batch_id = ? AND status = 'OPEN'""", BigDecimal.class, TenantContext.tenantId(), batchId);
        return due == null ? BigDecimal.ZERO : due;
    }

    /**
     * Put a group payment on the group's open orders, oldest number first, each taking what it still owes. Refused
     * (so nothing is recorded) if the money is more than the group owes, e.g. a table paid in cash meanwhile.
     */
    private void spreadOverGroup(UUID batchId, BigDecimal amount, String receipt) {
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id, total, paid FROM orders WHERE tenant_id = ? AND batch_id = ? AND status = 'OPEN'
                ORDER BY number FOR UPDATE""", TenantContext.tenantId(), batchId);
        BigDecimal left = amount;
        for (Map<String, Object> o : open) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal due = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid"));
            BigDecimal part = left.min(due);
            if (part.signum() <= 0) {
                continue;
            }
            orders.pay((UUID) o.get("id"), new Body(Map.of("payments", List.of(Map.of("method", "MPESA", "amount", part, "reference", receipt)))));
            left = left.subtract(part);
        }
        if (left.signum() > 0) {
            throw ApiException.conflict("KES " + left.toPlainString() + " more than the group still owed");
        }
    }

    /** Owner pays for a plan from Billing. The plan switches on as soon as the payment is confirmed. */
    public Map<String, Object> startSubscriptionPayment(UUID subscriptionRequestId, String phoneRaw) {
        requireEnabled();
        String phone = normalizePhone(phoneRaw);
        Map<String, Object> r = jdbc.queryForMap("SELECT amount, plan FROM subscription_requests WHERE id = ? AND tenant_id = ?",
                subscriptionRequestId, TenantContext.tenantId());
        PayAccount acct = cfg.subscriptionAccount();
        if (acct == null && !cfg.simulated()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_SETUP",
                    "Paying by M-Pesa prompt isn't ready yet. Send the payment to LeziTech and tap I've paid, and we switch your plan on.");
        }
        BigDecimal amount = (BigDecimal) r.get("amount");
        UUID id = insertRequest("SUBSCRIPTION", null, subscriptionRequestId, phone, amount);
        return push(id, acct, phone, amount, "BizNez " + r.get("plan"), "BizNez plan");
    }

    private UUID insertRequest(String purpose, UUID orderId, UUID subId, String phone, BigDecimal amount) {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return jdbc.queryForObject("""
                INSERT INTO mpesa_requests (tenant_id, purpose, order_id, subscription_request_id, user_id, phone, amount, callback_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                TenantContext.tenantId(), purpose, orderId, subId, TenantContext.userId(), phone, amount, HexFormat.of().formatHex(b));
    }

    /** Send the prompt through the account (null only in local simulation). */
    private Map<String, Object> push(UUID id, PayAccount acct, String phone, BigDecimal amount, String ref, String desc) {
        String token = jdbc.queryForObject("SELECT callback_token FROM mpesa_requests WHERE id = ?", String.class, id);
        jdbc.update("UPDATE mpesa_requests SET provider = ? WHERE id = ?", acct == null ? "DARAJA" : acct.provider(), id);
        try {
            StkResult res;
            if (cfg.simulated()) {
                res = simulator.stkPush(phone, amount, token);
            } else if (acct instanceof PayAccount.PayHero ph) {
                res = payHero.stkPush(ph, phone, amount, id.toString(), callbackBase() + "/api/public/payhero/" + token);
            } else {
                res = daraja.stkPush((PayAccount.Daraja) acct, phone, amount, ref, desc, callbackBase() + "/api/public/mpesa/stk/" + token);
            }
            jdbc.update("UPDATE mpesa_requests SET checkout_request_id = ?, merchant_request_id = ?, provider_ref = ?, updated_at = now() WHERE id = ?",
                    res.checkoutRequestId(), res.merchantRequestId(), res.providerRef(), id);
        } catch (ApiException e) {
            jdbc.update("UPDATE mpesa_requests SET status = 'FAILED', result_desc = ?, updated_at = now() WHERE id = ?", e.getMessage(), id);
            throw e;
        }
        return status(id);
    }

    /** What the till polls while the customer is entering their PIN. PayHero prompts are re-checked with PayHero every few seconds. */
    public Map<String, Object> status(UUID id) {
        if (!cfg.simulated()) {
            List<Map<String, Object>> pending = jdbc.queryForList("""
                    SELECT * FROM mpesa_requests WHERE id = ? AND tenant_id = ? AND status = 'PENDING' AND provider = 'PAYHERO'
                      AND provider_ref IS NOT NULL AND updated_at < now() - interval '4 seconds'""", id, TenantContext.tenantId());
            if (!pending.isEmpty()) {
                jdbc.update("UPDATE mpesa_requests SET updated_at = now() WHERE id = ?", id);
                try {
                    recheckPayHero(pending.get(0));
                } catch (RuntimeException e) {
                    log.info("PayHero status check for {} failed: {}", id, e.getMessage());
                }
            }
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, purpose, order_id AS "orderId", subscription_request_id AS "subscriptionRequestId", phone, amount, status,
                       receipt, result_desc AS "message", provider, created_at AS "createdAt"
                FROM mpesa_requests WHERE id = ? AND tenant_id = ?""", id, TenantContext.tenantId());
        if (rows.isEmpty()) {
            throw ApiException.notFound("M-Pesa request");
        }
        return rows.get(0);
    }

    /** The cashier gave up waiting. A late success is still recorded. */
    public Map<String, Object> cancel(UUID id) {
        jdbc.update("""
                UPDATE mpesa_requests SET status = 'CANCELLED', result_desc = 'Stopped waiting at the till', updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'PENDING'""", id, TenantContext.tenantId());
        return status(id);
    }

    /**
     * Safaricom's STK result. Public: the long random token in the URL is what proves it came from
     * a request we made. Safe to receive twice; the payment is recorded once.
     */
    public void handleStkCallback(String token, JsonNode payload) {
        JsonNode cb = payload.path("Body").path("stkCallback");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mpesa_requests WHERE callback_token = ?", token);
        if (rows.isEmpty()) {
            log.warn("M-Pesa callback with unknown token ignored");
            return;
        }
        Map<String, Object> r = rows.get(0);
        if (!cb.path("CheckoutRequestID").asText().equals(r.get("checkout_request_id"))) {
            log.warn("M-Pesa callback for request {} has a different checkout id, ignored", r.get("id"));
            return;
        }
        String receipt = null;
        BigDecimal paidAmount = null;
        for (JsonNode item : cb.path("CallbackMetadata").path("Item")) {
            switch (item.path("Name").asText()) {
                case "MpesaReceiptNumber" -> receipt = item.path("Value").asText();
                case "Amount" -> paidAmount = new BigDecimal(item.path("Value").asText("0"));
                default -> { }
            }
        }
        settle(r, cb.path("ResultCode").asInt(-1), cb.path("ResultDesc").asText(""), receipt, paidAmount);
    }

    /** PayHero's callback: unsigned, so it only tells us to ask PayHero for the real status. */
    public void handlePayHeroCallback(String token) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM mpesa_requests WHERE callback_token = ? AND provider = 'PAYHERO' AND status = 'PENDING'", token);
        if (!rows.isEmpty()) {
            recheckPayHero(rows.get(0));
        }
    }

    private void recheckPayHero(Map<String, Object> r) {
        String ref = (String) r.get("provider_ref");
        PayAccount.PayHero acct = "SUBSCRIPTION".equals(r.get("purpose"))
                ? (cfg.subscriptionAccount() instanceof PayAccount.PayHero p ? p : null)
                : integrations.payHeroAccount((UUID) r.get("tenant_id")).orElse(null);
        if (ref == null || acct == null) {
            return;
        }
        PayHeroGateway.Status st = payHero.status(acct, ref);
        switch (st.state()) {
            case "PAID" -> settle(r, 0, "Paid", st.receipt() == null ? ref : st.receipt(),
                    st.amount() == null ? (BigDecimal) r.get("amount") : st.amount());
            case "CANCELLED" -> settle(r, 1032, st.message(), null, null);
            case "FAILED" -> settle(r, 1, st.message(), null, null);
            default -> { }
        }
    }

    /** Record a provider's final answer for a prompt, once. Code 0 is paid; 1032 is the customer cancelling. */
    private void settle(Map<String, Object> r, int code, String desc, String receipt, BigDecimal paidAmount) {
        UUID id = (UUID) r.get("id");
        UUID tenant = (UUID) r.get("tenant_id");
        UUID user = (UUID) r.get("user_id");
        // Claim the result first, so a repeated callback can't record the money twice.
        int claimed = jdbc.update("UPDATE mpesa_requests SET result_code = ?, result_desc = ?, receipt = ?, updated_at = now() WHERE id = ? AND result_code IS NULL",
                code, desc, receipt, id);
        if (claimed == 0) {
            return;
        }
        if (code != 0) {
            jdbc.update("UPDATE mpesa_requests SET status = ? WHERE id = ?", code == 1032 ? "CANCELLED" : "FAILED", id);
            announce(r, "FAILED");
            return;
        }
        BigDecimal expected = (BigDecimal) r.get("amount");
        // Prompts charge whole shillings (rounded up), so it may be a few cents above what we asked.
        if (paidAmount == null || paidAmount.compareTo(expected.setScale(0, RoundingMode.FLOOR)) < 0) {
            jdbc.update("UPDATE mpesa_requests SET status = 'UNAPPLIED', result_desc = ? WHERE id = ?",
                    "Paid " + (paidAmount == null ? "?" : paidAmount.toPlainString()) + " but " + expected.toPlainString() + " was asked; check and record it by hand", id);
            announce(r, "UNAPPLIED");
            return;
        }
        if ("SUBSCRIPTION".equals(r.get("purpose"))) {
            tx.executeWithoutResult(s -> {
                plans.activate((UUID) r.get("subscription_request_id"), "MPESA", receipt);
                jdbc.update("UPDATE mpesa_requests SET status = 'PAID' WHERE id = ?", id);
            });
            log.info("Plan activated by M-Pesa {} for tenant {}", receipt, tenant);
            return;
        }
        if ("FOODCOURT".equals(r.get("purpose"))) {
            UUID fcOrder = (UUID) r.get("fc_order_id");
            TenantContext.runAs(tenant, user, () -> tx.execute(s -> {
                jdbc.update("UPDATE fc_orders SET status = 'PAID', paid_at = now(), receipt = ? WHERE id = ? AND status = 'WAITING_PAYMENT'", receipt, fcOrder);
                jdbc.update("UPDATE mpesa_requests SET status = 'PAID' WHERE id = ?", id);
                return null;
            }));
            return;
        }
        if ("LEASE".equals(r.get("purpose"))) {
            UUID leaseId = (UUID) r.get("lease_id");
            try {
                TenantContext.runAs(tenant, user, () -> tx.execute(s -> {
                    leases.receive(leaseId, paidAmount.max(expected), "MPESA", receipt, "PROMPT", null, null);
                    jdbc.update("UPDATE mpesa_requests SET status = 'PAID' WHERE id = ?", id);
                    return null;
                }));
            } catch (RuntimeException e) {
                log.warn("M-Pesa {} confirmed but couldn't be put on lease {}: {}", receipt, leaseId, e.getMessage());
                jdbc.update("UPDATE mpesa_requests SET status = 'UNAPPLIED', result_desc = ? WHERE id = ?",
                        "Money received but not recorded on the lease (" + e.getMessage() + "). Record it by hand with code " + receipt + ".", id);
            }
            return;
        }
        UUID orderId = (UUID) r.get("order_id");
        // A tip from the diner's phone rides on the prompt but isn't part of the sale; it's recorded on its own.
        BigDecimal tip = r.get("tip") == null ? BigDecimal.ZERO : (BigDecimal) r.get("tip");
        BigDecimal forSale = expected.subtract(tip);
        UUID batch = (UUID) r.get("batch_id");
        try {
            TenantContext.runAs(tenant, user, () -> tx.execute(s -> {
                if (batch != null) {
                    spreadOverGroup(batch, forSale, receipt);
                } else {
                    orders.pay(orderId, new Body(Map.of("payments", List.of(Map.of("method", "MPESA", "amount", forSale, "reference", receipt)))));
                }
                jdbc.update("UPDATE mpesa_requests SET status = 'PAID' WHERE id = ?", id);
                return null;
            }));
            announce(r, "PAID");
        } catch (RuntimeException e) {
            // e.g. the sale was paid in cash meanwhile. Keep the money visible so staff can refund or apply it.
            log.warn("M-Pesa {} confirmed but couldn't be put on order {}: {}", receipt, orderId, e.getMessage());
            jdbc.update("UPDATE mpesa_requests SET status = 'UNAPPLIED', result_desc = ? WHERE id = ?",
                    "Money received but the sale was already closed (" + e.getMessage() + "). Refund the customer or use it on another sale.", id);
            announce(r, "UNAPPLIED");
        }
    }

    private void announce(Map<String, Object> r, String outcome) {
        if (!"ORDER".equals(r.get("purpose")) || events == null) {
            return;
        }
        try {
            events.publishEvent(new OrderPromptSettled((UUID) r.get("tenant_id"), (UUID) r.get("order_id"), (UUID) r.get("id"),
                    (UUID) r.get("split_id"), r.get("tip") == null ? BigDecimal.ZERO : (BigDecimal) r.get("tip"), outcome,
                    (UUID) r.get("batch_id")));
        } catch (RuntimeException e) {
            log.warn("After M-Pesa result for request {}: {}", r.get("id"), e.toString());
        }
    }

    private void deliverSimulated(SimulatedGateway.SimulatedResult c) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mpesa_requests WHERE callback_token = ?", c.callbackToken());
            if (rows.isEmpty()) {
                return;
            }
            settle(rows.get(0), c.success() ? 0 : 1032,
                    c.success() ? "The service request is processed successfully." : "Request cancelled by user",
                    c.success() ? c.receipt() : null, c.success() ? c.amount().setScale(0, RoundingMode.CEILING) : null);
        } catch (RuntimeException e) {
            log.warn("Simulated M-Pesa result failed: {}", e.toString());
        }
    }

    // ---------------- C2B (customer pays the till number directly) ----------------

    public boolean c2bSecretOk(String secret) {
        return !cfg.c2bSecret.isBlank() && java.security.MessageDigest.isEqual(cfg.c2bSecret.getBytes(), secret.getBytes());
    }

    /** Store a direct payment and, if the account number is an open sale's number, put it on that sale. */
    public void handleC2bConfirmation(JsonNode p) {
        String transId = p.path("TransID").asText();
        if (transId.isBlank()) {
            return;
        }
        BigDecimal amount = new BigDecimal(p.path("TransAmount").asText("0"));
        String shortcode = p.path("BusinessShortCode").asText();
        String billRef = p.path("BillRefNumber").asText("").trim();
        List<UUID> tenants = integrations.tenantsForShortcode(shortcode);
        UUID tenant = tenants.size() == 1 ? tenants.get(0) : null;
        String payer = (p.path("FirstName").asText("") + " " + p.path("LastName").asText("")).trim();
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO mpesa_c2b (tenant_id, trans_id, amount, shortcode, bill_ref, msisdn, payer_name, trans_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (trans_id) DO NOTHING RETURNING id""", UUID.class,
                tenant, transId, amount, shortcode, billRef, p.path("MSISDN").asText(null), payer, p.path("TransTime").asText(null));
        if (inserted.isEmpty() || tenant == null) {
            return;
        }
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'OWNER' ORDER BY created_at LIMIT 1", UUID.class, tenant);
        // A landlord's tenant types their unit's account number (e.g. G12): the money goes on that lease.
        try {
            Boolean onLease = TenantContext.runAs(tenant, owner, () -> tx.execute(s -> {
                Optional<UUID> lease = leases.leaseForAccount(billRef);
                if (lease.isEmpty()) {
                    // A landlord's tenant who mistyped their unit (e.g. "GO5") must not land on invoice #5 of someone else.
                    Long landlord = jdbc.queryForObject("SELECT count(*) FROM leases WHERE tenant_id = ? AND status = 'ACTIVE'", Long.class, tenant);
                    return landlord != null && landlord > 0 && billRef.matches(".*[A-Za-z].*") ? null : false;
                }
                leases.receive(lease.get(), amount, "MPESA", transId, "PAYBILL", payer.isBlank() ? null : payer, null);
                jdbc.update("UPDATE mpesa_c2b SET status = 'APPLIED', lease_id = ? WHERE id = ?", lease.get(), inserted.get(0));
                return true;
            }));
            if (!Boolean.FALSE.equals(onLease)) {
                return;   // put on a lease, or left in the landlord's list to assign
            }
        } catch (RuntimeException e) {
            log.info("C2B {} for account {} left for staff to match: {}", transId, billRef, e.getMessage());
            return;
        }
        String digits = billRef.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return;
        }
        try {
            TenantContext.runAs(tenant, owner, () -> tx.execute(s -> {
                // orders has row-level security, so look it up inside the workspace's transaction
                List<UUID> open = jdbc.queryForList("SELECT id FROM orders WHERE tenant_id = ? AND number = ? AND status = 'OPEN' AND kind <> 'QUOTE'",
                        UUID.class, tenant, Integer.parseInt(digits));
                return open.size() == 1 ? apply(inserted.get(0), open.get(0)) : null;
            }));
        } catch (RuntimeException e) {
            log.info("C2B {} left for staff to match: {}", transId, e.getMessage());
        }
    }

    public List<Map<String, Object>> c2bList(String status) {
        return jdbc.queryForList("""
                SELECT c.id, c.trans_id AS "receipt", c.amount, c.bill_ref AS "account", c.msisdn AS "phone", c.payer_name AS "payer",
                       c.status, c.created_at AS "createdAt", o.number AS "orderNumber"
                FROM mpesa_c2b c LEFT JOIN orders o ON o.id = c.order_id
                WHERE c.tenant_id = ? AND (? = 'ALL' OR c.status = ?) ORDER BY c.created_at DESC LIMIT 100""",
                TenantContext.tenantId(), status, status);
    }

    /** Put a direct till payment on an open sale (staff matched it by hand, or the account number fit). */
    public Map<String, Object> apply(UUID c2bId, UUID orderId) {
        return tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mpesa_c2b WHERE id = ? AND tenant_id = ? FOR UPDATE",
                    c2bId, TenantContext.tenantId());
            if (rows.isEmpty()) {
                throw ApiException.notFound("M-Pesa payment");
            }
            Map<String, Object> c = rows.get(0);
            if (!"UNMATCHED".equals(c.get("status"))) {
                throw ApiException.conflict("This M-Pesa payment was already used");
            }
            Map<String, Object> o = orders.requireOpen(orderId);
            BigDecimal due = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid"));
            BigDecimal amount = (BigDecimal) c.get("amount");
            if (amount.compareTo(due) > 0) {
                throw ApiException.bad("This payment (KES " + amount.toPlainString() + ") is more than the KES " + due.toPlainString() + " due on that sale");
            }
            Map<String, Object> result = orders.pay(orderId, new Body(Map.of("payments",
                    List.of(Map.of("method", "MPESA", "amount", amount, "reference", c.get("trans_id"))))));
            jdbc.update("UPDATE mpesa_c2b SET status = 'APPLIED', order_id = ? WHERE id = ?", orderId, c2bId);
            return result;
        });
    }

    /** The landlord says whose an unmatched paybill payment is: it goes on that lease like any other. */
    public Map<String, Object> applyToLease(UUID c2bId, UUID leaseId) {
        return tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mpesa_c2b WHERE id = ? AND tenant_id = ? FOR UPDATE",
                    c2bId, TenantContext.tenantId());
            if (rows.isEmpty()) {
                throw ApiException.notFound("M-Pesa payment");
            }
            Map<String, Object> c = rows.get(0);
            if (!"UNMATCHED".equals(c.get("status"))) {
                throw ApiException.conflict("This M-Pesa payment was already used");
            }
            Map<String, Object> out = leases.receive(leaseId, (BigDecimal) c.get("amount"), "MPESA", (String) c.get("trans_id"), "PAYBILL",
                    (String) c.get("payer_name"), null);
            jdbc.update("UPDATE mpesa_c2b SET status = 'APPLIED', lease_id = ? WHERE id = ?", leaseId, c2bId);
            return out;
        });
    }

    public void ignore(UUID c2bId) {
        jdbc.update("UPDATE mpesa_c2b SET status = 'IGNORED' WHERE id = ? AND tenant_id = ? AND status = 'UNMATCHED'", c2bId, TenantContext.tenantId());
    }

    // ---------------- helpers ----------------

    private void requireEnabled() {
        if (!cfg.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_OFF",
                    "M-Pesa prompts aren't switched on for this system yet. Take the payment on your phone and type the M-Pesa code instead.");
        }
    }

    private String callbackBase() {
        if (cfg.callbackBase.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_SETUP", "MPESA_CALLBACK_BASE isn't set, so M-Pesa couldn't tell us the result.");
        }
        return cfg.callbackBase.replaceAll("/+$", "");
    }

    /** 0712 345 678 / +254712345678 / 254712345678 / 0112... -> 254712345678 */
    static String normalizePhone(String raw) {
        String d = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (d.startsWith("0") && d.length() == 10) {
            d = "254" + d.substring(1);
        } else if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        if (!d.matches("^254[17][0-9]{8}$")) {
            throw ApiException.bad("Enter the customer's M-Pesa number, like 0712 345 678");
        }
        return d;
    }
}
