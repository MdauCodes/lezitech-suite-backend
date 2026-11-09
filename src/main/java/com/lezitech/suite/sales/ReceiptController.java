package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.integrations.IntegrationService;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.notify.WhatsAppCloud;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Email a sale's receipt (or a quotation) to the customer, in the business's name. */
@RestController
public class ReceiptController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.of("Africa/Nairobi"));

    private final OrderService orders;
    private final Mailer mailer;
    private final JdbcTemplate jdbc;
    private final IntegrationService integrations;
    private final WhatsAppCloud whatsApp;

    public ReceiptController(OrderService orders, Mailer mailer, JdbcTemplate jdbc, IntegrationService integrations, WhatsAppCloud whatsApp) {
        this.orders = orders;
        this.mailer = mailer;
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.whatsApp = whatsApp;
    }

    @PostMapping("/api/orders/{id}/email-receipt")
    @Transactional
    public Map<String, Object> email(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        String to = b.str("email").trim().toLowerCase();
        if (!EMAIL.matcher(to).matches()) {
            throw ApiException.bad("Enter the customer's email address");
        }
        if (!mailer.on()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_OFF", "Emails aren't switched on yet. Use WhatsApp or print the receipt.");
        }
        Map<String, Object> o = orders.detail(id);
        Map<String, Object> t = jdbc.queryForMap("SELECT name, phone, currency, settings->>'address' AS address, settings->>'kraPin' AS kra,"
                + " settings->>'receiptFooter' AS footer FROM tenants WHERE id = ?", TenantContext.tenantId());
        String business = (String) t.get("name");
        String cur = (String) t.get("currency");
        boolean quote = "QUOTE".equals(o.get("kind"));
        String title = (quote ? "Quotation" : "Receipt") + " #" + o.get("number");
        Object when = o.get("closedAt") != null ? o.get("closedAt") : o.get("createdAt");
        String date;
        try {
            date = WHEN.format(java.time.Instant.parse(String.valueOf(when)));
        } catch (RuntimeException e) {
            date = String.valueOf(when);
        }

        StringBuilder text = new StringBuilder(business + "\n" + title + " · " + date + "\n\n");
        StringBuilder rows = new StringBuilder();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) o.get("lines");
        for (Map<String, Object> l : lines) {
            String qty = ((BigDecimal) l.get("qty")).stripTrailingZeros().toPlainString();
            String amount = money(cur, (BigDecimal) l.get("lineTotal"));
            text.append(qty).append(" x ").append(l.get("name")).append("  ").append(amount).append('\n');
            rows.append("<tr><td style=\"padding:4px 0\">").append(qty).append(" × ").append(Mailer.esc((String) l.get("name")))
                    .append("</td><td style=\"padding:4px 0;text-align:right\">").append(amount).append("</td></tr>");
        }
        BigDecimal tax = (BigDecimal) o.get("tax");
        text.append("\nTotal: ").append(money(cur, (BigDecimal) o.get("total")));
        if (tax != null && tax.signum() > 0) {
            text.append(" (includes VAT ").append(money(cur, tax)).append(")");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payments = (List<Map<String, Object>>) o.get("payments");
        StringBuilder paid = new StringBuilder();
        for (Map<String, Object> p : payments) {
            String line = method((String) p.get("method")) + ": " + money(cur, (BigDecimal) p.get("amount"))
                    + (p.get("reference") == null ? "" : " (" + p.get("reference") + ")");
            text.append('\n').append(line);
            paid.append("<div>").append(Mailer.esc(line)).append("</div>");
        }
        String footer = t.get("footer") == null ? "Thank you!" : (String) t.get("footer");
        text.append("\n\n").append(footer);

        String html = "<p style=\"margin:0;font-size:18px\"><b>" + Mailer.esc(business) + "</b></p>"
                + (t.get("address") == null || ((String) t.get("address")).isBlank() ? "" : "<div style=\"color:#6b7280\">" + Mailer.esc((String) t.get("address")) + "</div>")
                + (t.get("phone") == null ? "" : "<div style=\"color:#6b7280\">Tel " + Mailer.esc((String) t.get("phone")) + "</div>")
                + (t.get("kra") == null || ((String) t.get("kra")).isBlank() ? "" : "<div style=\"color:#6b7280\">KRA PIN " + Mailer.esc((String) t.get("kra")) + "</div>")
                + "<p style=\"margin:16px 0 4px\"><b>" + title + "</b> · " + Mailer.esc(date) + "</p>"
                + "<table style=\"width:100%;border-collapse:collapse;border-top:1px solid #e5e7eb;border-bottom:1px solid #e5e7eb\">" + rows + "</table>"
                + "<p style=\"font-size:17px;margin:10px 0 4px\"><b>Total " + money(cur, (BigDecimal) o.get("total")) + "</b></p>"
                + (tax != null && tax.signum() > 0 ? "<div style=\"color:#6b7280\">Includes VAT " + money(cur, tax) + "</div>" : "")
                + paid + "<p>" + Mailer.esc(footer) + "</p>";
        String status = mailer.send(to, null, title + " from " + business, text.toString(), html);
        if (!"SENT".equals(status)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EMAIL_FAILED", "The email didn't go through. Check the address, or use WhatsApp.");
        }
        // Remember the address on the customer, if the sale has one and they had none.
        if (o.get("customerId") != null) {
            jdbc.update("UPDATE customers SET email = ? WHERE id = ? AND tenant_id = ? AND (email IS NULL OR email = '')",
                    to, o.get("customerId"), TenantContext.tenantId());
        }
        return Map.of("ok", true, "to", to);
    }

    /** Whether receipts go through the business's own WhatsApp Business number (true) or wa.me links on the cashier's phone. */
    @GetMapping("/api/whatsapp/status")
    public Map<String, Object> whatsAppStatus() {
        return Map.of("api", integrations.whatsAppAccount(TenantContext.tenantId()).isPresent());
    }

    /**
     * Send the receipt from the business's WhatsApp Business number, as its approved receipt template:
     * {{1}} business, {{2}} receipt number and date, {{3}} the items, {{4}} the total and how it was paid.
     */
    @PostMapping("/api/orders/{id}/whatsapp-receipt")
    @Transactional
    public Map<String, Object> whatsApp(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        String to = kenyanPhone(new Body(raw).str("phone"));
        if (to == null) {
            throw ApiException.bad("Enter the customer's phone number, e.g. 0712 345678");
        }
        IntegrationService.WhatsAppAccount wa = integrations.whatsAppAccount(TenantContext.tenantId()).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "WHATSAPP_OFF", "WhatsApp Business isn't switched on. Use the WhatsApp link instead."));
        Map<String, Object> o = orders.detail(id);
        Map<String, Object> t = jdbc.queryForMap("SELECT name, currency FROM tenants WHERE id = ?", TenantContext.tenantId());
        String cur = (String) t.get("currency");
        boolean quote = "QUOTE".equals(o.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) o.get("lines");
        StringBuilder items = new StringBuilder();
        for (Map<String, Object> l : lines) {
            if (!items.isEmpty()) {
                items.append(", ");
            }
            items.append(((BigDecimal) l.get("qty")).stripTrailingZeros().toPlainString()).append(" x ").append(l.get("name"))
                    .append(' ').append(money(cur, (BigDecimal) l.get("lineTotal")));
        }
        StringBuilder total = new StringBuilder(money(cur, (BigDecimal) o.get("total")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payments = (List<Map<String, Object>>) o.get("payments");
        if (payments != null && !payments.isEmpty()) {
            total.append(", paid by ").append(String.join(" and ", payments.stream().map(p -> method((String) p.get("method"))
                    + (p.get("reference") == null ? "" : " " + p.get("reference"))).toList()));
        }
        String number = (quote ? "Quotation" : "Receipt") + " #" + o.get("number") + ", " + when(o);
        String messageId = whatsApp.sendTemplate(wa.phoneNumberId(), wa.accessToken(), to, wa.receiptTemplate(), wa.language(),
                List.of((String) t.get("name"), number, items.toString(), total.toString()));
        if (o.get("customerId") != null) {
            jdbc.update("UPDATE customers SET phone = ? WHERE id = ? AND tenant_id = ? AND (phone IS NULL OR phone = '')",
                    to.startsWith("254") ? "0" + to.substring(3) : "+" + to, o.get("customerId"), TenantContext.tenantId());
        }
        return Map.of("ok", true, "to", "+" + to, "messageId", messageId);
    }

    /** 0712345678, 712345678, +254712345678 -> 254712345678; other countries' numbers in full international form pass as they are. */
    static String kenyanPhone(String raw) {
        String d = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (d.length() == 10 && d.startsWith("0")) {
            d = "254" + d.substring(1);
        } else if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        return d.matches("[1-9][0-9]{9,14}") ? d : null;
    }

    private static String when(Map<String, Object> o) {
        Object w = o.get("closedAt") != null ? o.get("closedAt") : o.get("createdAt");
        try {
            return WHEN.format(java.time.Instant.parse(String.valueOf(w)));
        } catch (RuntimeException e) {
            return String.valueOf(w);
        }
    }

    private static String money(String cur, BigDecimal v) {
        return (cur == null ? "KES" : cur) + " " + String.format(Locale.ENGLISH, "%,.2f", v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String method(String m) {
        return switch (m == null ? "" : m) {
            case "MPESA" -> "M-Pesa";
            case "CASH" -> "Cash";
            case "CARD" -> "Card";
            case "CREDIT", "ACCOUNT" -> "On account";
            default -> m == null ? "" : m.charAt(0) + m.substring(1).toLowerCase(Locale.ROOT);
        };
    }
}
