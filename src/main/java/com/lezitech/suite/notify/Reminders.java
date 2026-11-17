package com.lezitech.suite.notify;

import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Messages to a business's customers about something coming up (an appointment tomorrow, a renewal...), sent once
 * per thing, kind and channel. Email works today. SMS and WhatsApp (through Africa's Talking) plug in later as more
 * {@link Channel}s; until they do, a business that chose them still gets email so nothing is missed.
 *
 * Call inside the business's transaction (TenantContext set); the row in {@code reminders} is the record.
 */
@Service
public class Reminders {

    /** One way of reaching a customer. Returns null when sent, else what went wrong. */
    public interface Channel {
        String name();

        boolean canReach(Contact c);

        String send(UUID tenant, Contact c, Message m);
    }

    public record Contact(String name, String email, String phone) {
    }

    /** subject is for email; text is the plain message every channel can use; html is the email body. */
    public record Message(String subject, String text, String html) {
    }

    private final Db db;
    private final List<Channel> channels;

    public Reminders(Db db, Mailer mailer) {
        this.db = db;
        this.channels = List.of(new EmailChannel(mailer));
    }

    /** The channel this business wants (settings.reminders.channel), if it can reach the customer; else email. */
    Optional<Channel> pick(Contact c) {
        String wanted = db.value("SELECT coalesce(settings->'reminders'->>'channel', 'EMAIL') FROM tenants WHERE id = :t", String.class);
        return channels.stream().filter(ch -> ch.name().equals(wanted) && ch.canReach(c)).findFirst()
                .or(() -> channels.stream().filter(ch -> ch.canReach(c)).findFirst());
    }

    /**
     * Send unless this reminder was already sent (or is being sent). Returns SENT, FAILED, ALREADY or NO_CONTACT.
     */
    public String sendOnce(String refKind, UUID refId, String kind, Contact c, Message m) {
        Optional<Channel> ch = pick(c);
        if (ch.isEmpty()) {
            return "NO_CONTACT";
        }
        String to = "EMAIL".equals(ch.get().name()) ? c.email() : c.phone();
        UUID row = db.value("""
                INSERT INTO reminders (tenant_id, ref_kind, ref_id, kind, channel, sent_to)
                VALUES (:t, :rk, :ri, :k, :ch, :to)
                ON CONFLICT (ref_kind, ref_id, kind, channel) DO NOTHING RETURNING id""", UUID.class,
                "rk", refKind, "ri", refId, "k", kind, "ch", ch.get().name(), "to", to);
        if (row == null) {
            return "ALREADY";
        }
        String error = ch.get().send(TenantContext.tenantId(), c, m);
        db.exec("UPDATE reminders SET status = :s, error = :e, sent_at = CASE WHEN :s = 'SENT' THEN now() END WHERE id = :id",
                "s", error == null ? "SENT" : "FAILED", "e", error, "id", row);
        return error == null ? "SENT" : "FAILED";
    }

    /** What was sent for one thing, newest first (shown to staff). */
    public List<Map<String, Object>> history(String refKind, UUID refId) {
        return db.list("""
                SELECT kind, channel, sent_to, status, error, created_at, sent_at FROM reminders
                WHERE ref_kind = :rk AND ref_id = :ri ORDER BY created_at DESC""", "rk", refKind, "ri", refId);
    }

    /** Email, in the business's own name and colours. */
    static final class EmailChannel implements Channel {
        private final Mailer mailer;

        EmailChannel(Mailer mailer) {
            this.mailer = mailer;
        }

        public String name() {
            return "EMAIL";
        }

        public boolean canReach(Contact c) {
            return c.email() != null && !c.email().isBlank();
        }

        public String send(UUID tenant, Contact c, Message m) {
            return mailer.trySendAs(tenant, c.email(), c.name(), m.subject(), m.text(), m.html());
        }
    }
}
