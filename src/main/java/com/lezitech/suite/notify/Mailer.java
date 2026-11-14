package com.lezitech.suite.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place emails leave BizNez: trial reminders, sign-in codes, welcome emails, receipts and
 * alerts to the LeziTech team. EMAIL_PROVIDER picks the service: off, log (local), brevo or resend.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final String appUrl;
    private final String support;
    private final String teamEmail;
    private EmailSender sender;

    public Mailer(ObjectMapper json,
                  @Value("${suite.email.provider:off}") String provider,
                  @Value("${suite.email.api-key:}") String apiKey,
                  @Value("${suite.email.from:}") String from,
                  @Value("${suite.email.from-name:LeziTech}") String fromName,
                  @Value("${suite.app-url:http://localhost:5320}") String appUrl,
                  @Value("${suite.support-whatsapp:}") String support,
                  @Value("${suite.team-email:}") String teamEmail) {
        this.appUrl = appUrl.replaceAll("/+$", "");
        this.support = support;
        this.teamEmail = teamEmail;
        this.sender = switch (provider) {
            case "log" -> new EmailSender.LogSender();
            case "brevo" -> apiKey.isBlank() || from.isBlank() ? null : new EmailSender.BrevoSender(apiKey, from, fromName, json);
            case "resend" -> apiKey.isBlank() || from.isBlank() ? null : new EmailSender.ResendSender(apiKey, from, fromName, json);
            default -> null;
        };
        if (sender == null && !"off".equals(provider)) {
            log.warn("EMAIL_PROVIDER={} needs EMAIL_API_KEY and EMAIL_FROM; emails are off", provider);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private BrandedEmail branded;

    /**
     * Send in a business's own name and look (logo, colours, contacts, sender name, replies to the business). The body
     * is just the message; the layout is added here. Returns null when sent, otherwise what went wrong.
     */
    public String trySendAs(java.util.UUID tenant, String to, String toName, String subject, String text, String bodyHtml) {
        if (sender == null) {
            return "Emails aren't switched on for this system";
        }
        if (to == null || to.isBlank()) {
            return "No email address";
        }
        BrandedEmail.Brand b = branded.brand(tenant);
        try {
            sender.send(new EmailSender.Email(to, toName, subject, text, BrandedEmail.page(b, bodyHtml, firstLine(text)), java.util.List.of(),
                    b.name(), b.email()));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** For tests: swap the email service. */
    public void useSender(EmailSender s) {
        this.sender = s;
    }

    public EmailSender sender() {
        return sender;
    }

    public boolean on() {
        return sender != null;
    }

    public String appUrl() {
        return appUrl;
    }

    /** LeziTech's WhatsApp/phone number, e.g. 0796 814 154; blank if not set. */
    public String support() {
        return support;
    }

    public String teamEmail() {
        return teamEmail;
    }

    /** Send now. Returns SENT, OFF, NO_EMAIL or FAILED (never throws). */
    public String send(String to, String toName, String subject, String text, String bodyHtml) {
        if (sender == null) {
            return "OFF";
        }
        if (to == null || to.isBlank()) {
            return "NO_EMAIL";
        }
        try {
            sender.send(new EmailSender.Email(to, toName, subject, text, layout(bodyHtml)));
            return "SENT";
        } catch (RuntimeException e) {
            log.warn("Email '{}' to {} failed: {}", subject, to, e.getMessage());
            return "FAILED";
        }
    }

    /**
     * Send a complete email as it is (no LeziTech wrapper): used for a business's own receipts and invoices.
     * Returns null when sent, otherwise what went wrong.
     */
    public String trySend(String to, String toName, String subject, String text, String fullHtml) {
        return trySend(to, toName, subject, text, fullHtml, java.util.List.of());
    }

    /** As above, with files attached (e.g. the business's ETR). */
    public String trySend(String to, String toName, String subject, String text, String fullHtml, java.util.List<EmailSender.Attachment> files) {
        if (sender == null) {
            return "Emails aren't switched on for this system";
        }
        if (to == null || to.isBlank()) {
            return "No email address";
        }
        try {
            sender.send(new EmailSender.Email(to, toName, subject, text, fullHtml, files));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /**
     * BizNez's own emails to business owners (codes, trial reminders, alerts), in the same card layout as the
     * businesses' emails: our mark on top, the message in a white card, our contact details below.
     */
    public String layout(String bodyHtml) {
        String mark = "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" align=\"center\"><tr>"
                + "<td width=\"44\" height=\"44\" align=\"center\" valign=\"middle\" bgcolor=\"#b8f24a\" style=\"width:44px;height:44px;border-radius:12px;"
                + "background:#b8f24a;color:#101415;font:bold 22px Arial,Helvetica,sans-serif\">B</td></tr></table>";
        return EmailLayout.page(new EmailLayout.Look("BizNez", "#101415", "#f4f2ec", mark,
                "BizNez by LeziTech" + (support.isBlank() ? "" : " · WhatsApp or call " + esc(support)), null), bodyHtml, null);
    }

    /**
     * Send an email in a business's own name: their brand on the email, their name as the sender, replies to their
     * email. Returns SENT, OFF, NO_EMAIL or FAILED (never throws).
     */
    public String sendAs(BrandedEmail.Brand brand, String to, String toName, String subject, String text, String bodyHtml) {
        if (sender == null) {
            return "OFF";
        }
        if (to == null || to.isBlank()) {
            return "NO_EMAIL";
        }
        try {
            sender.send(new EmailSender.Email(to, toName, subject, text, BrandedEmail.page(brand, bodyHtml, firstLine(text)), java.util.List.of(),
                    brand.name(), brand.email()));
            return "SENT";
        } catch (RuntimeException e) {
            log.warn("Email '{}' to {} failed: {}", subject, to, e.getMessage());
            return "FAILED";
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() > 110 ? t.substring(0, 110) : t;
    }

    public static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
