package com.lezitech.suite.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
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
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lipa na M-Pesa for the till and for Lezitech subscriptions.
 *
 * Flow: the till asks for a prompt (STK push) -> the customer enters their PIN -> Safaricom posts
 * the result to a secret callback URL -> we check it against the request we sent (checkout id and
 * amount) and only then record the payment on the order, once. Direct till payments (C2B) are
 * stored and matched to an open order by its number when the customer typed it as the account.
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
    private final MpesaGateway gateway;

    public MpesaService(MpesaConfig cfg, JdbcTemplate jdbc, OrderService orders, PlanActivation plans,
                        PlatformTransactionManager txm, ObjectMapper json) {
        this.cfg = cfg;
        this.jdbc = jdbc;
        this.orders = orders;
        this.plans = plans;
        this.tx = new TransactionTemplate(txm);
        this.gateway = cfg.simulated()
                ? new SimulatedGateway(cfg.simulateDelayMs, this::deliverSimulated)
                : new DarajaGateway(cfg, json);
    }

    public Map<String, Object> publicConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled());
        m.put("mode", cfg.mode());
        m.put("simulated", cfg.simulated());
        return m;
    }

    // ---------------- STK push ----------------

    /** Send a "pay with M-Pesa" prompt for what's still due on an open order (or part of it). */
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
        UUID id = insertRequest("ORDER", orderId, null, phone, amount);
        return push(id, cfg.shortcode, phone, amount, "Sale " + o.get("number"), "Payment");
    }

    /** Owner pays for a plan from Billing. The plan switches on as soon as Safaricom confirms. */
    public Map<String, Object> startSubscriptionPayment(UUID subscriptionRequestId, String phoneRaw) {
        requireEnabled();
        String phone = normalizePhone(phoneRaw);
        Map<String, Object> r = jdbc.queryForMap("SELECT amount, plan FROM subscription_requests WHERE id = ? AND tenant_id = ?",
                subscriptionRequestId, TenantContext.tenantId());
        BigDecimal amount = (BigDecimal) r.get("amount");
        UUID id = insertRequest("SUBSCRIPTION", null, subscriptionRequestId, phone, amount);
        return push(id, cfg.subscriptionShortcode, phone, amount, "Lezitech " + r.get("plan"), "Lezitech plan");
    }

    private UUID insertRequest(String purpose, UUID orderId, UUID subId, String phone, BigDecimal amount) {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return jdbc.queryForObject("""
                INSERT INTO mpesa_requests (tenant_id, purpose, order_id, subscription_request_id, user_id, phone, amount, callback_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                TenantContext.tenantId(), purpose, orderId, subId, TenantContext.userId(), phone, amount, HexFormat.of().formatHex(b));
    }

    private Map<String, Object> push(UUID id, String shortcode, String phone, BigDecimal amount, String ref, String desc) {
        String token = jdbc.queryForObject("SELECT callback_token FROM mpesa_requests WHERE id = ?", String.class, id);
        String callback = callbackBase() + "/api/public/mpesa/stk/" + token;
        try {
            MpesaGateway.StkResult res = gateway.stkPush(shortcode, phone, amount, ref, desc, callback);
            jdbc.update("UPDATE mpesa_requests SET checkout_request_id = ?, merchant_request_id = ?, updated_at = now() WHERE id = ?",
                    res.checkoutRequestId(), res.merchantRequestId(), id);
        } catch (ApiException e) {
            jdbc.update("UPDATE mpesa_requests SET status = 'FAILED', result_desc = ?, updated_at = now() WHERE id = ?", e.getMessage(), id);
            throw e;
        }
        return status(id);
    }

    /** What the till polls while the customer is entering their PIN. */
    public Map<String, Object> status(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, purpose, order_id AS "orderId", subscription_request_id AS "subscriptionRequestId", phone, amount, status,
                       receipt, result_desc AS "message", created_at AS "createdAt"
                FROM mpesa_requests WHERE id = ? AND tenant_id = ?""", id, TenantContext.tenantId());
        if (rows.isEmpty()) {
            throw ApiException.notFound("M-Pesa request");
        }
        return rows.get(0);
    }

    /** The cashier gave up waiting. A late success from Safaricom is still recorded. */
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
        UUID id = (UUID) r.get("id");
        String checkout = cb.path("CheckoutRequestID").asText();
        if (!checkout.equals(r.get("checkout_request_id"))) {
            log.warn("M-Pesa callback for request {} has a different checkout id, ignored", id);
            return;
        }
        int code = cb.path("ResultCode").asInt(-1);
        String desc = cb.path("ResultDesc").asText("");
        String receipt = null;
        BigDecimal paidAmount = null;
        for (JsonNode item : cb.path("CallbackMetadata").path("Item")) {
            switch (item.path("Name").asText()) {
                case "MpesaReceiptNumber" -> receipt = item.path("Value").asText();
                case "Amount" -> paidAmount = new BigDecimal(item.path("Value").asText("0"));
                default -> { }
            }
        }
        UUID tenant = (UUID) r.get("tenant_id");
        UUID user = (UUID) r.get("user_id");
        final String rec = receipt;
        final BigDecimal amt = paidAmount;
        // Claim the result first, so a repeated callback can't record the money twice.
        int claimed = jdbc.update("UPDATE mpesa_requests SET result_code = ?, result_desc = ?, receipt = ?, updated_at = now() WHERE id = ? AND result_code IS NULL",
                code, desc, rec, id);
        if (claimed == 0) {
            return;
        }
        if (code != 0) {
            jdbc.update("UPDATE mpesa_requests SET status = ? WHERE id = ?", code == 1032 ? "CANCELLED" : "FAILED", id);
            return;
        }
        BigDecimal expected = (BigDecimal) r.get("amount");
        // Daraja charges whole shillings (rounded up), so it may be a few cents above what we asked.
        if (amt == null || amt.compareTo(expected.setScale(0, java.math.RoundingMode.FLOOR)) < 0) {
            jdbc.update("UPDATE mpesa_requests SET status = 'UNAPPLIED', result_desc = ? WHERE id = ?",
                    "Paid " + (amt == null ? "?" : amt.toPlainString()) + " but " + expected.toPlainString() + " was asked; check and record it by hand", id);
            return;
        }
        if ("SUBSCRIPTION".equals(r.get("purpose"))) {
            tx.executeWithoutResult(s -> {
                plans.activate((UUID) r.get("subscription_request_id"), "MPESA", rec);
                jdbc.update("UPDATE mpesa_requests SET status = 'PAID' WHERE id = ?", id);
            });
            log.info("Plan activated by M-Pesa {} for tenant {}", rec, tenant);
            return;
        }
        UUID orderId = (UUID) r.get("order_id");
        try {
            TenantContext.runAs(tenant, user, () -> tx.execute(s -> {
                orders.pay(orderId, new Body(Map.of("payments", List.of(Map.of("method", "MPESA", "amount", expected, "reference", rec)))));
                jdbc.update("UPDATE mpesa_requests SET status = 'PAID' WHERE id = ?", id);
                return null;
            }));
        } catch (RuntimeException e) {
            // e.g. the sale was paid in cash meanwhile. Keep the money visible so staff can refund or apply it.
            log.warn("M-Pesa {} confirmed but couldn't be put on order {}: {}", rec, orderId, e.getMessage());
            jdbc.update("UPDATE mpesa_requests SET status = 'UNAPPLIED', result_desc = ? WHERE id = ?",
                    "Money received but the sale was already closed (" + e.getMessage() + "). Refund the customer or use it on another sale.", id);
        }
    }

    private void deliverSimulated(SimulatedGateway.SimulatedCallback c) {
        String token = c.callbackUrl().substring(c.callbackUrl().lastIndexOf('/') + 1);
        Map<String, Object> stk = new LinkedHashMap<>();
        stk.put("MerchantRequestID", c.merchantRequestId());
        stk.put("CheckoutRequestID", c.checkoutRequestId());
        stk.put("ResultCode", c.success() ? 0 : 1032);
        stk.put("ResultDesc", c.success() ? "The service request is processed successfully." : "Request cancelled by user");
        if (c.success()) {
            stk.put("CallbackMetadata", Map.of("Item", List.of(
                    Map.of("Name", "Amount", "Value", c.amount().setScale(0, java.math.RoundingMode.CEILING)),
                    Map.of("Name", "MpesaReceiptNumber", "Value", c.receipt()),
                    Map.of("Name", "PhoneNumber", "Value", c.phone()))));
        }
        try {
            handleStkCallback(token, new ObjectMapper().valueToTree(Map.of("Body", Map.of("stkCallback", stk))));
        } catch (RuntimeException e) {
            log.warn("Simulated M-Pesa callback failed: {}", e.toString());
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
        List<UUID> tenants = jdbc.queryForList("SELECT id FROM tenants WHERE settings->>'mpesaTill' = ?", UUID.class, shortcode);
        UUID tenant = tenants.size() == 1 ? tenants.get(0) : null;
        String payer = (p.path("FirstName").asText("") + " " + p.path("LastName").asText("")).trim();
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO mpesa_c2b (tenant_id, trans_id, amount, shortcode, bill_ref, msisdn, payer_name, trans_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (trans_id) DO NOTHING RETURNING id""", UUID.class,
                tenant, transId, amount, shortcode, billRef, p.path("MSISDN").asText(null), payer, p.path("TransTime").asText(null));
        if (inserted.isEmpty() || tenant == null) {
            return;
        }
        String digits = billRef.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return;
        }
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'OWNER' ORDER BY created_at LIMIT 1", UUID.class, tenant);
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
        if (cfg.simulated()) {
            return "http://simulator";
        }
        if (cfg.callbackBase.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MPESA_SETUP", "MPESA_CALLBACK_BASE isn't set, so Safaricom couldn't tell us the result.");
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
