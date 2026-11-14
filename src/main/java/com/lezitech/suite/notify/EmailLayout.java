package com.lezitech.suite.notify;

/**
 * The one card layout every email uses (the Ushirika model): a mark or logo centred above a white rounded card on a
 * soft page, a footer with real contact details. Built only from tables and inline styles, so Gmail, Outlook and phone
 * mail apps keep the design (no style blocks, no embedded images, no web fonts, no flex or grid).
 */
public final class EmailLayout {

    /** name: shown under the mark. accent: headings and links. page: the background around the card. */
    public record Look(String name, String accent, String page, String markHtml, String footerHtml, String footerLine2) {
    }

    private EmailLayout() {
    }

    public static String page(Look l, String bodyHtml, String preheader) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"x-apple-disable-message-reformatting\"><title>" + Mailer.esc(l.name()) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:" + l.page() + "\">"
                // Shown as the preview line in the inbox, hidden in the email itself.
                + (preheader == null ? "" : "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:" + l.page() + "\">" + Mailer.esc(preheader) + "</div>")
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"" + l.page() + "\" style=\"background:" + l.page() + "\">"
                + "<tr><td align=\"center\" style=\"padding:28px 12px\">"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:600px\">"
                + "<tr><td align=\"center\" style=\"padding:0 0 18px\">" + l.markHtml()
                + "<div style=\"font:bold 19px Arial,Helvetica,sans-serif;color:" + l.accent() + ";margin-top:10px\">" + Mailer.esc(l.name()) + "</div></td></tr>"
                + "<tr><td bgcolor=\"#ffffff\" style=\"background:#ffffff;border-radius:16px;padding:28px 26px;border:1px solid #e7e3da;"
                + "font:15px/1.55 Arial,Helvetica,sans-serif;color:#1d1d1b\">" + bodyHtml + "</td></tr>"
                + "<tr><td align=\"center\" style=\"padding:20px 10px 0;font:12px/1.6 Arial,Helvetica,sans-serif;color:#6b6b66\">" + l.footerHtml()
                + (l.footerLine2() == null ? "" : "<br>" + l.footerLine2()) + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /** A button that works in every mail app (a coloured table cell with a link, not a styled link alone). */
    public static String button(String label, String url, String color) {
        String c = color == null ? "#101415" : color;
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:22px 0 6px\"><tr>"
                + "<td bgcolor=\"" + c + "\" style=\"border-radius:10px;background:" + c + "\">"
                + "<a href=\"" + Mailer.esc(url) + "\" target=\"_blank\" style=\"display:inline-block;padding:13px 24px;font:bold 15px Arial,Helvetica,sans-serif;"
                + "color:" + BrandedEmail.inkOn(c) + ";text-decoration:none;border-radius:10px\">" + Mailer.esc(label) + "</a></td></tr></table>";
    }

    /** A short list of facts, e.g. Date / Time / Guests, as a two-column table. */
    public static String facts(String[][] rows) {
        StringBuilder s = new StringBuilder("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" "
                + "style=\"margin:16px 0;border-collapse:collapse;background:#f7f6f2;border-radius:12px\">");
        for (String[] r : rows) {
            s.append("<tr><td style=\"padding:9px 14px;font:13px Arial,Helvetica,sans-serif;color:#6b6b66;width:38%\">").append(Mailer.esc(r[0]))
                    .append("</td><td style=\"padding:9px 14px;font:bold 14px Arial,Helvetica,sans-serif;color:#1d1d1b\">").append(Mailer.esc(r[1])).append("</td></tr>");
        }
        return s.append("</table>").toString();
    }

    public static String p(String html) {
        return "<p style=\"margin:0 0 14px\">" + html + "</p>";
    }

    public static String h(String text, String color) {
        return "<h1 style=\"margin:0 0 14px;font:bold 21px/1.3 Arial,Helvetica,sans-serif;color:" + (color == null ? "#1d1d1b" : color) + "\">" + Mailer.esc(text) + "</h1>";
    }
}
