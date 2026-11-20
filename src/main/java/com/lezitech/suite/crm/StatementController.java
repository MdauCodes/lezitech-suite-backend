package com.lezitech.suite.crm;

import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What customers owe: the "who owes you" list with how old each debt is, a statement for any dates, a link the
 * customer opens (in the business's colours, no sign-in), and the statement by email.
 */
@RestController
public class StatementController {

    /** Statement views from one phone or network: 60 an hour. */
    public static final RateLimit PER_CLIENT = new RateLimit(60, 3_600_000);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final CustomerLedger ledger;
    private final Mailer mailer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public StatementController(Db db, CustomerLedger ledger, Mailer mailer, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.db = db;
        this.ledger = ledger;
        this.mailer = mailer;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    /** Everyone who owes something, biggest first, with how old it is; and the totals. */
    @GetMapping("/api/customers/owing")
    @Transactional(readOnly = true)
    public Map<String, Object> owing() {
        List<Map<String, Object>> rows = db.list("""
                SELECT c.id, c.name, c.phone, c.email, c.balance, c.credit_limit,
                       (SELECT max(g.created_at) FROM customer_ledger g WHERE g.customer_id = c.id AND g.kind = 'PAYMENT') AS last_paid_at
                FROM customers c WHERE c.balance > 0 ORDER BY c.balance DESC LIMIT 500""");
        BigDecimal total = BigDecimal.ZERO, over60 = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            Map<String, Object> age = ledger.ageing(UUID.fromString(r.get("id").toString()), (BigDecimal) r.get("balance"));
            r.put("ageing", age);
            total = total.add((BigDecimal) r.get("balance"));
            over60 = over60.add((BigDecimal) age.get("days61to90")).add((BigDecimal) age.get("over90"));
        }
        return Map.of("customers", rows, "total", total, "over60", over60, "count", rows.size());
    }

    @GetMapping("/api/customers/{id}/statement")
    @Transactional(readOnly = true)
    public Map<String, Object> statement(@PathVariable UUID id, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return ledger.statement(id, from, to);
    }

    /** The customer's own link to their statement (made once, then kept). */
    @PostMapping("/api/customers/{id}/statement/link")
    @Transactional
    public Map<String, Object> link(@PathVariable UUID id) {
        Map<String, Object> c = db.one("Customer", "SELECT id, name, phone, statement_token FROM customers WHERE id = :id", "id", id);
        String token = (String) c.get("statementToken");
        if (token == null) {
            byte[] b = new byte[24];
            RANDOM.nextBytes(b);
            token = HexFormat.of().formatHex(b);
            db.exec("UPDATE customers SET statement_token = :tok WHERE id = :id", "id", id, "tok", token);
        }
        String slug = db.value("SELECT slug FROM tenants WHERE id = :t", String.class);
        return Map.of("url", mailer.appUrl() + "/account-statement/" + slug + "/" + token);
    }

    /** Email the statement (the last 90 days) with the link, in the business's name and look. */
    @PostMapping("/api/customers/{id}/statement/email")
    @Transactional
    public Map<String, Object> email(@PathVariable UUID id) {
        Map<String, Object> c = db.one("Customer", "SELECT id, name, email, balance FROM customers WHERE id = :id", "id", id);
        String to = (String) c.get("email");
        if (to == null || to.isBlank()) {
            throw ApiException.bad("Add " + c.get("name") + "'s email first, or send the statement on WhatsApp");
        }
        String url = (String) link(id).get("url");
        String business = db.value("SELECT name FROM tenants WHERE id = :t", String.class);
        BigDecimal balance = (BigDecimal) c.get("balance");
        String owes = "KES " + String.format("%,.2f", balance);
        String name = String.valueOf(c.get("name")).trim().split("\\s+")[0];
        String text = "Hi " + name + ",\n\nHere is your statement from " + business + ". "
                + (balance.signum() > 0 ? "You owe " + owes + "." : "You owe nothing. Thank you!") + "\n\nSee every sale and payment: " + url + "\n\n" + business;
        String html = "<p>Hi " + Mailer.esc(name) + ",</p><p>Here is your statement from <b>" + Mailer.esc(business) + "</b>.</p>"
                + "<p style=\"font-size:22px;font-weight:bold;margin:8px 0\">" + (balance.signum() > 0 ? "You owe " + Mailer.esc(owes) : "You owe nothing. Thank you!") + "</p>"
                + "<p><a href=\"" + url + "\" style=\"display:inline-block;background:#101415;color:#ffffff;padding:10px 16px;border-radius:8px;text-decoration:none\">See my statement</a></p>";
        String problem = mailer.trySendAs(TenantContext.tenantId(), to, (String) c.get("name"), "Your statement from " + business, text, html);
        if (problem != null) {
            throw ApiException.bad("The email didn't go: " + problem);
        }
        return Map.of("ok", true, "message", "Statement emailed to " + to);
    }

    /** The customer's statement from their link: no sign-in; the long random part of the link is the key. */
    @GetMapping("/api/public/account-statement/{slug}/{token}")
    public Map<String, Object> publicStatement(@PathVariable String slug, @PathVariable String token,
                                               @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                               HttpServletRequest req) {
        if (!PER_CLIENT.allow(ClientIp.of(req))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many tries. Please wait a while and open the link again.");
        }
        if (token == null || !token.matches("[0-9a-f]{32,64}")) {
            throw ApiException.notFound("Statement");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, phone, brand_color, logo_url, status, settings->>'address' AS address, settings->>'mpesaTill' AS till
                FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Statement");
        }
        Map<String, Object> t = rows.get(0);
        UUID tenant = (UUID) t.get("id");
        Map<String, Object> s = TenantContext.runAs(tenant, null, () -> tx.execute(x -> {
            List<UUID> c = jdbc.queryForList("SELECT id FROM customers WHERE tenant_id = ? AND statement_token = ?", UUID.class, tenant, token);
            if (c.isEmpty()) {
                throw ApiException.notFound("Statement");
            }
            return ledger.statement(c.get(0), from, to);
        }));
        Map<String, Object> out = new LinkedHashMap<>(s);
        // Only what the customer needs: no internal ids or limits.
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) s.get("customer");
        out.put("customer", Map.of("name", c.get("name"), "company", c.get("company") == null ? "" : c.get("company")));
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("name", t.get("name"));
        business.put("phone", t.get("phone"));
        business.put("address", t.get("address"));
        business.put("till", t.get("till"));
        business.put("brandColor", t.get("brand_color"));
        business.put("logoUrl", t.get("logo_url"));
        out.put("business", business);
        return out;
    }
}
