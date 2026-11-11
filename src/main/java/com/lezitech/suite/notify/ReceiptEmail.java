package com.lezitech.suite.notify;

import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A receipt, invoice or quotation as an email, in the business's own name, logo and colour. The customer sees the
 * business, not us. Built inside the business's transaction (the order is fenced by row-level security).
 */
@Component
public class ReceiptEmail {

    public record Built(String subject, String text, String html, String businessName, UUID customerId) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.of("Africa/Nairobi"));

    private final OrderService orders;
    private final JdbcTemplate jdbc;

    public ReceiptEmail(OrderService orders, JdbcTemplate jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    public Built build(UUID orderId) {
        Map<String, Object> o = orders.detail(orderId);
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT name, phone, currency, brand_color, logo_url, settings->>'address' AS address, settings->>'kraPin' AS kra,
                       settings->>'receiptFooter' AS footer, settings->>'mpesaTill' AS till FROM tenants WHERE id = ?""", TenantContext.tenantId());
        String business = (String) t.get("name");
        String cur = t.get("currency") == null ? "KES" : (String) t.get("currency");
        String brand = t.get("brand_color") == null ? "#101415" : (String) t.get("brand_color");
        boolean quote = "QUOTE".equals(o.get("kind"));
        String status = (String) o.get("status");
        BigDecimal total = (BigDecimal) o.get("total");
        BigDecimal paid = o.get("paid") == null ? BigDecimal.ZERO : (BigDecimal) o.get("paid");
        BigDecimal balance = total.subtract(paid).max(BigDecimal.ZERO);
        String kind = quote ? "Quotation" : balance.signum() > 0 ? "Invoice" : "Receipt";
        String title = kind + " #" + o.get("number");
        Object when = o.get("closedAt") != null ? o.get("closedAt") : o.get("createdAt");
        String date;
        try {
            date = WHEN.format(when instanceof java.sql.Timestamp ts ? ts.toInstant() : Instant.parse(String.valueOf(when)));
        } catch (RuntimeException e) {
            date = String.valueOf(when);
        }

        StringBuilder text = new StringBuilder(business + "\n" + title + " · " + date + "\n\n");
        StringBuilder rows = new StringBuilder();
        for (Map<String, Object> l : (List<Map<String, Object>>) o.get("lines")) {
            String qty = ((BigDecimal) l.get("qty")).stripTrailingZeros().toPlainString();
            String amount = money(cur, (BigDecimal) l.get("lineTotal"));
            text.append(qty).append(" x ").append(l.get("name")).append("  ").append(amount).append('\n');
            rows.append("<tr><td style=\"padding:8px 0;border-bottom:1px solid #eef0ea\">").append(Mailer.esc((String) l.get("name")))
                    .append("<div style=\"color:#6b7280;font-size:13px\">").append(qty).append(" × ").append(money(cur, (BigDecimal) l.get("unitPrice"))).append("</div></td>")
                    .append("<td style=\"padding:8px 0;border-bottom:1px solid #eef0ea;text-align:right;white-space:nowrap\">").append(amount).append("</td></tr>");
        }
        BigDecimal tax = (BigDecimal) o.get("tax");
        BigDecimal discount = o.get("discount") == null ? BigDecimal.ZERO : (BigDecimal) o.get("discount");
        text.append("\nTotal: ").append(money(cur, total));
        StringBuilder sums = new StringBuilder();
        if (discount.signum() > 0) {
            sums.append(sumRow("Discount", "-" + money(cur, discount), false));
        }
        if (tax != null && tax.signum() > 0) {
            sums.append(sumRow("VAT included", money(cur, tax), false));
            text.append(" (includes VAT ").append(money(cur, tax)).append(")");
        }
        sums.append(sumRow("Total", money(cur, total), true));
        StringBuilder paidHtml = new StringBuilder();
        if (!quote) {
            for (Map<String, Object> p : (List<Map<String, Object>>) o.get("payments")) {
                String line = method((String) p.get("method")) + (p.get("reference") == null ? "" : " " + p.get("reference"));
                sums.append(sumRow(line, money(cur, (BigDecimal) p.get("amount")), false));
                text.append('\n').append(method((String) p.get("method"))).append(": ").append(money(cur, (BigDecimal) p.get("amount")))
                        .append(p.get("reference") == null ? "" : " (" + p.get("reference") + ")");
            }
            if (balance.signum() > 0) {
                sums.append(sumRow("Balance due", money(cur, balance), true));
                text.append("\nBalance due: ").append(money(cur, balance));
                if (t.get("till") != null && !((String) t.get("till")).isBlank()) {
                    paidHtml.append("<p style=\"margin:14px 0 0\">Pay by M-Pesa to <b>").append(Mailer.esc((String) t.get("till")))
                            .append("</b>, account <b>").append(o.get("number")).append("</b>.</p>");
                }
            }
        } else {
            paidHtml.append("<p style=\"margin:14px 0 0;color:#6b7280\">This quotation is valid for 14 days unless stated otherwise.</p>");
        }
        String footer = t.get("footer") == null || ((String) t.get("footer")).isBlank() ? "Thank you for your business." : (String) t.get("footer");
        text.append("\n\n").append(footer);

        String logo = (String) t.get("logo_url");
        // Most email apps block embedded (data:) images, so only a hosted logo is used; otherwise the initials in the brand colour.
        String mark = logo != null && logo.startsWith("http")
                ? "<img src=\"" + Mailer.esc(logo) + "\" alt=\"\" width=\"44\" height=\"44\" style=\"border-radius:10px;object-fit:contain;background:#fff\">"
                : "<span style=\"display:inline-block;width:44px;height:44px;border-radius:10px;background:" + Mailer.esc(brand)
                        + ";color:#fff;font-weight:bold;font-size:18px;text-align:center;line-height:44px\">" + Mailer.esc(initials(business)) + "</span>";
        String info = (t.get("address") == null || ((String) t.get("address")).isBlank() ? "" : Mailer.esc((String) t.get("address")) + "<br>")
                + (t.get("phone") == null ? "" : "Tel " + Mailer.esc((String) t.get("phone")) + "<br>")
                + (t.get("kra") == null || ((String) t.get("kra")).isBlank() ? "" : "KRA PIN " + Mailer.esc((String) t.get("kra")));
        String customer = o.get("customerName") == null ? "" : "<p style=\"margin:0 0 12px;color:#6b7280\">" + (quote ? "Prepared for " : "For ")
                + "<b style=\"color:#101415\">" + Mailer.esc((String) o.get("customerName")) + "</b></p>";
        String html = "<div style=\"background:#f3f4ef;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;color:#101415\">"
                + "<div style=\"max-width:560px;margin:0 auto;background:#fff;border-radius:16px;overflow:hidden;border:1px solid #e4e6df\">"
                + "<div style=\"height:6px;background:" + Mailer.esc(brand) + "\"></div>"
                + "<div style=\"padding:22px 24px\">"
                + "<table style=\"width:100%;border-collapse:collapse\"><tr><td style=\"vertical-align:top\">" + mark + "</td>"
                + "<td style=\"vertical-align:top;padding-left:12px\"><div style=\"font-size:18px;font-weight:bold\">" + Mailer.esc(business) + "</div>"
                + "<div style=\"color:#6b7280;font-size:13px;line-height:1.5\">" + info + "</div></td>"
                + "<td style=\"vertical-align:top;text-align:right\"><div style=\"font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:" + Mailer.esc(brand)
                + ";font-weight:bold\">" + kind + "</div><div style=\"font-size:13px;color:#6b7280\">No. " + o.get("number") + "<br>" + Mailer.esc(date) + "</div></td></tr></table>"
                + "<div style=\"height:18px\"></div>" + customer
                + "<table style=\"width:100%;border-collapse:collapse;font-size:15px\">" + rows + "</table>"
                + "<table style=\"width:100%;border-collapse:collapse;margin-top:10px;font-size:15px\">" + sums + "</table>"
                + paidHtml
                + "<p style=\"margin:22px 0 0;padding-top:14px;border-top:1px solid #eef0ea;color:#6b7280\">" + Mailer.esc(footer) + "</p>"
                + "</div></div></div>";
        UUID customerId = o.get("customerId") == null ? null : UUID.fromString(o.get("customerId").toString());
        return new Built(title + " from " + business, text.toString(), html, business, customerId);
    }

    private static String sumRow(String k, String v, boolean bold) {
        String w = bold ? "font-weight:bold;font-size:17px;" : "color:#6b7280;";
        return "<tr><td style=\"padding:3px 0;" + w + "\">" + Mailer.esc(k) + "</td><td style=\"padding:3px 0;text-align:right;" + w + "\">" + Mailer.esc(v) + "</td></tr>";
    }

    private static String initials(String name) {
        StringBuilder s = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (!w.isEmpty() && s.length() < 2) {
                s.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        return s.toString();
    }

    static String money(String cur, BigDecimal v) {
        return cur + " " + String.format(Locale.ENGLISH, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }

    static String method(String m) {
        return switch (m) {
            case "CASH" -> "Cash";
            case "MPESA" -> "M-Pesa";
            case "CARD" -> "Card";
            case "CREDIT" -> "On account";
            default -> m;
        };
    }
}
