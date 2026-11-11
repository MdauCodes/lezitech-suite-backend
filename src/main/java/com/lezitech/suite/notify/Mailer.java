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
        if (sender == null) {
            return "Emails aren't switched on for this system";
        }
        if (to == null || to.isBlank()) {
            return "No email address";
        }
        try {
            sender.send(new EmailSender.Email(to, toName, subject, text, fullHtml));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** A plain, readable email: LeziTech mark on top, the message, a short footer. */
    public String layout(String bodyHtml) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#101415;max-width:560px;margin:0 auto;padding:16px\">"
                + "<div style=\"margin-bottom:18px\"><span style=\"display:inline-block;width:26px;height:26px;border-radius:6px;background:#b8f24a;"
                + "color:#101415;font-weight:bold;text-align:center;line-height:26px\">B</span> <b>BizNez</b></div>"
                + bodyHtml
                + "<p style=\"margin-top:28px;font-size:12px;color:#6b7280\">BizNez by LeziTech"
                + (support.isBlank() ? "" : " · WhatsApp or call " + esc(support)) + "</p></div>";
    }

    public static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
