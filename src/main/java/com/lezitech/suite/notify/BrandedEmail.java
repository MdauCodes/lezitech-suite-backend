package com.lezitech.suite.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emails from a business to its own customers (a booking, a reminder, a statement) look like that business: its
 * logo (or initials in its colour), its name as the sender, replies going to its email, its phone and address in the
 * footer. BizNez only appears as a small "sent with BizNez" line.
 */
@Component
public class BrandedEmail {

    public record Brand(String name, String color, String logoUrl, String phone, String email, String address) {
    }

    private final JdbcTemplate jdbc;
    private final String publicUrl;

    public BrandedEmail(JdbcTemplate jdbc, @Value("${suite.public-api-url:${suite.app-url:http://localhost:5320}}") String publicUrl) {
        this.jdbc = jdbc;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    /** The business's look and contacts. Reads the tenants table directly (no row-level security there). */
    public Brand brand(UUID tenant) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT t.name, t.slug, t.brand_color, t.logo_url, t.phone, t.settings->>'address' AS address,
                       coalesce(nullif(t.settings->>'contactEmail', ''),
                                (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.email IS NOT NULL
                                 ORDER BY u.created_at LIMIT 1)) AS email
                FROM tenants t WHERE t.id = ?""", tenant);
        String logo = (String) t.get("logo_url");
        String hosted = logo == null || logo.isBlank() ? null
                : logo.startsWith("http") ? logo
                : publicUrl + "/api/public/logo/" + t.get("slug") + "?v=" + Integer.toHexString(logo.hashCode());
        return new Brand((String) t.get("name"), t.get("brand_color") == null ? "#0f766e" : (String) t.get("brand_color"), hosted,
                (String) t.get("phone"), (String) t.get("email"), (String) t.get("address"));
    }

    public static String page(Brand b, String bodyHtml, String preheader) {
        String mark = b.logoUrl() != null
                ? "<img src=\"" + Mailer.esc(b.logoUrl()) + "\" alt=\"" + Mailer.esc(b.name()) + "\" width=\"64\" height=\"64\" "
                + "style=\"display:inline-block;width:64px;height:64px;border-radius:14px;background:#ffffff;border:0\">"
                : "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" align=\"center\"><tr>"
                + "<td width=\"56\" height=\"56\" align=\"center\" valign=\"middle\" bgcolor=\"" + b.color() + "\" style=\"width:56px;height:56px;border-radius:14px;"
                + "background:" + b.color() + ";color:" + inkOn(b.color()) + ";font:bold 21px Arial,Helvetica,sans-serif\">" + Mailer.esc(initials(b.name())) + "</td></tr></table>";
        String contacts = String.join(" · ", List.of(
                b.phone() == null || b.phone().isBlank() ? "" : "<a href=\"tel:" + Mailer.esc(b.phone().replace(" ", "")) + "\" style=\"color:" + b.color() + ";text-decoration:none\">" + Mailer.esc(b.phone()) + "</a>",
                b.email() == null || b.email().isBlank() ? "" : "<a href=\"mailto:" + Mailer.esc(b.email()) + "\" style=\"color:" + b.color() + ";text-decoration:none\">" + Mailer.esc(b.email()) + "</a>")
                .stream().filter(s -> !s.isEmpty()).toList());
        String footer = "<b style=\"color:#1d1d1b\">" + Mailer.esc(b.name()) + "</b>"
                + (b.address() == null || b.address().isBlank() ? "" : "<br>" + Mailer.esc(b.address()))
                + (contacts.isEmpty() ? "" : "<br>" + contacts);
        return EmailLayout.page(new EmailLayout.Look(b.name(), darken(b.color()), tint(b.color()), mark, footer,
                "<span style=\"color:#9a9a93\">Sent with BizNez</span>"), bodyHtml, preheader);
    }

    // ---------------- colours ----------------

    /** Black or white text, whichever reads better on this colour. */
    public static String inkOn(String hex) {
        int[] c = rgb(hex);
        double l = (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255;
        return l > 0.6 ? "#101415" : "#ffffff";
    }

    /** A very light wash of the brand colour for the page around the card. */
    static String tint(String hex) {
        int[] c = rgb(hex);
        return String.format("#%02x%02x%02x", mix(c[0], 0.09), mix(c[1], 0.09), mix(c[2], 0.09));
    }

    /** The brand colour, darkened if it's too light to read as text. */
    static String darken(String hex) {
        int[] c = rgb(hex);
        double l = (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255;
        if (l < 0.55) {
            return hex;
        }
        return String.format("#%02x%02x%02x", (int) (c[0] * 0.45), (int) (c[1] * 0.45), (int) (c[2] * 0.45));
    }

    private static int mix(int v, double share) {
        return (int) Math.round(255 - (255 - v) * share);
    }

    private static int[] rgb(String hex) {
        try {
            String h = hex.replace("#", "");
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            return new int[]{Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16)};
        } catch (RuntimeException e) {
            return new int[]{15, 118, 110};
        }
    }

    private static String initials(String name) {
        StringBuilder s = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0)) && s.length() < 2) {
                s.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        return s.length() == 0 ? "B" : s.toString();
    }
}
