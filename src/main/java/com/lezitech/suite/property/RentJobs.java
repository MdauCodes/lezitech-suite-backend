package com.lezitech.suite.property;

import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/**
 * Every hour, from 8 in the morning (Nairobi), for each landlord: raise the month's invoices (if they chose that),
 * add penalties to late invoices on leases that have them, and email tenants their reminders. Each reminder is claimed
 * in {@code rent_reminders} before it's sent, so it goes once even if two servers run the job.
 */
@Service
public class RentJobs {

    private static final Logger log = LoggerFactory.getLogger(RentJobs.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PropertyController property;
    private final LeaseLedger ledger;
    private final LeaseLife life;
    private final Mailer mailer;
    @org.springframework.beans.factory.annotation.Autowired
    private Market market;

    public RentJobs(JdbcTemplate jdbc, PlatformTransactionManager txm, PropertyController property, LeaseLedger ledger, LeaseLife life, Mailer mailer) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.property = property;
        this.ledger = ledger;
        this.life = life;
        this.mailer = mailer;
    }

    @Scheduled(cron = "${suite.rent.cron:0 5 * * * *}", zone = "Africa/Nairobi")
    public void hourly() {
        if (ZonedDateTime.now(LeaseLedger.NAIROBI).getHour() < 8) {
            return;
        }
        run(LeaseLedger.today());
    }

    /** One pass over every landlord for {@code today}. Returns the number of reminders sent. */
    public int run(LocalDate today) {
        int sent = 0;
        for (Map<String, Object> t : jdbc.queryForList("""
                SELECT t.id, (SELECT u.id FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' ORDER BY u.created_at LIMIT 1) AS owner
                FROM tenants t
                WHERE t.sector = 'property' AND NOT t.in_setup
                  AND (t.status = 'ACTIVE' OR (t.status = 'TRIAL' AND t.trial_ends_at > now()))""")) {
            UUID tenant = (UUID) t.get("id");
            try {
                sent += forTenant(tenant, (UUID) t.get("owner"), today);
            } catch (RuntimeException e) {
                log.warn("Rent jobs for tenant {} failed: {}", tenant, e.toString());
            }
        }
        return sent;
    }

    /** The day's rent work for one landlord. Public for tests. */
    public int forTenant(UUID tenant, UUID owner, LocalDate today) {
        List<Map<String, Object>> increases = new ArrayList<>();
        List<Map<String, Object>> ending = new ArrayList<>();
        Map<String, Object> settings = TenantContext.runAs(tenant, owner, () -> tx.execute(s -> {
            Map<String, Object> rs = RentSettings.withDefaults(jdbc.queryForObject(
                    "SELECT coalesce(settings->'rent', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, tenant));
            increases.addAll(life.proposeIncreases(today));
            ending.addAll(life.endAlertsDue(today));
            if (Boolean.TRUE.equals(rs.get("autoInvoice"))) {
                property.raiseInvoices(YearMonth.from(today));
            }
            ledger.raisePenalties(today);
            market.raiseStallCharges(today, rs);
            return rs;
        }));
        if (!increases.isEmpty() || !ending.isEmpty()) {
            alertOwner(tenant, increases, ending);
        }
        if (!Boolean.TRUE.equals(settings.get("emailReminders"))) {
            return 0;
        }
        List<Reminder> due = TenantContext.runAs(tenant, owner, () -> tx.execute(s -> pick(tenant, settings, today)));
        int sent = 0;
        for (Reminder r : due) {
            String error = send(r, settings);
            String status = error == null ? "SENT" : "FAILED";
            TenantContext.runAs(tenant, owner, () -> tx.execute(s -> jdbc.update(
                    "UPDATE rent_reminders SET status = ?, error = ? WHERE order_id = ? AND kind = ? AND channel = 'EMAIL'",
                    status, error == null ? null : error.length() > 300 ? error.substring(0, 300) : error, r.orderId(), r.kind())));
            if ("SENT".equals(status)) {
                sent++;
            }
        }
        return sent;
    }

    /** One email to the owner: rent increases waiting for their approval, and leases coming to an end. */
    private void alertOwner(UUID tenant, List<Map<String, Object>> increases, List<Map<String, Object>> ending) {
        List<Map<String, Object>> owners = jdbc.queryForList("""
                SELECT u.email, u.name FROM users u WHERE u.tenant_id = ? AND u.role = 'OWNER' AND u.active AND u.email IS NOT NULL
                ORDER BY u.created_at LIMIT 1""", tenant);
        if (owners.isEmpty()) {
            return;
        }
        String link = mailer.appUrl() + "/property";
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        if (!increases.isEmpty()) {
            text.append("Rent increases waiting for your approval:\n");
            html.append("<p><b>Rent increases waiting for your approval</b></p><ul>");
            for (Map<String, Object> i : increases) {
                String line = i.get("unitName") + " (" + i.get("tenantName") + "): " + LeaseLife.kes((BigDecimal) i.get("rent")) + " to "
                        + LeaseLife.kes((BigDecimal) i.get("newRent")) + " from " + DAY.format(LocalDate.parse(i.get("nextIncreaseOn").toString()));
                text.append("- ").append(line).append('\n');
                html.append("<li>").append(esc(line)).append("</li>");
            }
            text.append("Nothing changes until you approve. You can change the amount or skip it.\n\n");
            html.append("</ul><p>Nothing changes until you approve. You can change the amount or skip it.</p>");
        }
        if (!ending.isEmpty()) {
            text.append("Leases coming to an end:\n");
            html.append("<p><b>Leases coming to an end</b></p><ul>");
            for (Map<String, Object> e : ending) {
                String line = e.get("unitName") + " (" + e.get("tenantName") + "): ends " + DAY.format(LocalDate.parse(e.get("endsOn").toString()))
                        + ", in " + e.get("daysLeft") + " days";
                text.append("- ").append(line).append('\n');
                html.append("<li>").append(esc(line)).append("</li>");
            }
            text.append("Renew it, or record the move-out. If you do nothing, rent carries on being billed monthly.\n\n");
            html.append("</ul><p>Renew it, or record the move-out. If you do nothing, rent carries on being billed monthly.</p>");
        }
        text.append("Open Rent & tenants: ").append(link);
        html.append("<p><a href=\"").append(esc(link)).append("\">Open Rent &amp; tenants</a></p>");
        Map<String, Object> o = owners.get(0);
        String subject = !increases.isEmpty() && !ending.isEmpty() ? "Rent increases to approve and leases ending"
                : !increases.isEmpty() ? "Rent increases waiting for your approval" : "Leases coming to an end";
        mailer.send((String) o.get("email"), (String) o.get("name"), subject, "Hi,\n\n" + text, "<p>Hi,</p>" + html);
    }

    record Reminder(UUID tenant, UUID orderId, String kind, String to, String name, String unit, String account, int number,
                    BigDecimal unpaid, BigDecimal balance, LocalDate dueOn, long daysLate, String business, String phone,
                    String brand, String link) {
    }

    /** The rent invoices that need a reminder today, each claimed so it's sent only once. */
    private List<Reminder> pick(UUID tenant, Map<String, Object> settings, LocalDate today) {
        int before = ((Number) settings.get("daysBefore")).intValue();
        boolean onDue = Boolean.TRUE.equals(settings.get("onDueDay"));
        List<Integer> after = new ArrayList<>();
        for (Object o : (List<?>) settings.get("daysAfter")) {
            after.add(((Number) o).intValue());
        }
        after.sort(null);
        Map<String, Object> biz = jdbc.queryForMap("SELECT name, phone, slug, brand_color FROM tenants WHERE id = ?", tenant);
        List<Reminder> out = new ArrayList<>();
        for (Map<String, Object> o : jdbc.queryForList("""
                SELECT o.id, o.number, o.total - o.paid AS unpaid, o.due_on, l.id AS lease_id, l.statement_token,
                       c.name, c.email, sp.name AS unit, """ + LeaseLedger.CODE_SQL + """
                 AS account
                FROM orders o JOIN leases l ON l.id = o.lease_id JOIN customers c ON c.id = l.customer_id JOIN spaces sp ON sp.id = l.space_id
                WHERE o.tenant_id = ? AND o.status = 'OPEN' AND o.kind = 'INVOICE' AND o.channel = 'RENT' AND o.total > o.paid
                  AND o.due_on IS NOT NULL AND o.due_on > cast(? as date) - 120""", tenant, java.sql.Date.valueOf(today))) {
            LocalDate dueOn = ((java.sql.Date) o.get("due_on")).toLocalDate();
            long late = ChronoUnit.DAYS.between(dueOn, today);
            String kind = null;
            if (late < 0 && before > 0 && -late <= before) {
                kind = "BEFORE";
            } else if (late == 0 && onDue) {
                kind = "DUE";
            } else if (late > 0) {
                // The latest step reached; one missed while the server was off isn't sent late as well.
                for (int d : after) {
                    if (late >= d) {
                        kind = "AFTER_" + d;
                    }
                }
            }
            if (kind == null) {
                continue;
            }
            String email = (String) o.get("email");
            String status = email == null || email.isBlank() ? "NO_EMAIL" : mailer.on() ? "PENDING" : "OFF";
            int claimed = jdbc.update("""
                    INSERT INTO rent_reminders (tenant_id, order_id, kind, channel, sent_to, status) VALUES (?, ?, ?, 'EMAIL', ?, ?)
                    ON CONFLICT (order_id, kind, channel) DO NOTHING""", tenant, o.get("id"), kind, email, status);
            if (claimed == 0 || !"PENDING".equals(status)) {
                continue;
            }
            UUID leaseId = (UUID) o.get("lease_id");
            out.add(new Reminder(tenant, (UUID) o.get("id"), kind, email.trim(), (String) o.get("name"), (String) o.get("unit"),
                    (String) o.get("account"), ((Number) o.get("number")).intValue(), (BigDecimal) o.get("unpaid"),
                    ledger.balance(leaseId), dueOn, late, (String) biz.get("name"), (String) biz.get("phone"),
                    biz.get("brand_color") == null ? "#101415" : (String) biz.get("brand_color"),
                    mailer.appUrl() + "/statement/" + biz.get("slug") + "/" + o.get("statement_token")));
        }
        return out;
    }

    /** Returns null when sent, otherwise what went wrong. */
    private String send(Reminder r, Map<String, Object> settings) {
        String first = r.name() == null ? "" : r.name().trim().split("\\s+")[0];
        String amount = "KES " + NumberFormat.getIntegerInstance(Locale.ENGLISH).format(r.unpaid().setScale(0, java.math.RoundingMode.HALF_UP));
        String when = DAY.format(r.dueOn());
        String subject;
        String lead;
        if ("BEFORE".equals(r.kind())) {
            subject = "Rent for " + r.unit() + " is due on " + when;
            lead = "a friendly reminder that " + amount + " for " + r.unit() + " is due on " + when + ".";
        } else if ("DUE".equals(r.kind())) {
            subject = "Rent for " + r.unit() + " is due today";
            lead = "your payment of " + amount + " for " + r.unit() + " is due today.";
        } else {
            subject = "Rent for " + r.unit() + " is " + r.daysLate() + " days overdue";
            lead = "we haven't yet received " + amount + " for " + r.unit() + ", which was due on " + when + ".";
        }
        String total = r.balance().compareTo(r.unpaid()) > 0
                ? " In all, " + "KES " + NumberFormat.getIntegerInstance(Locale.ENGLISH).format(r.balance().setScale(0, java.math.RoundingMode.HALF_UP))
                  + " is unpaid on your account." : "";
        String howRaw = (String) settings.get("payInstructions");
        String how = howRaw == null || howRaw.isBlank()
                ? "When paying by M-Pesa paybill, use account number " + r.account() + "."
                : howRaw + " Account number: " + r.account() + ".";
        String text = "Hi " + first + ",\n\nThis is " + r.business() + ": " + lead + total + "\n\n" + how
                + "\n\nYour statement: " + r.link() + "\n\nIf you've already paid, thank you, and please ignore this message."
                + (r.phone() == null ? "" : "\nQuestions? Call " + r.phone() + ".") + "\n\n" + r.business();
        String html = "<div style=\"background:#f3f4ef;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;color:#101415\">"
                + "<div style=\"max-width:560px;margin:0 auto;background:#fff;border-radius:16px;overflow:hidden;border:1px solid #e4e6df\">"
                + "<div style=\"height:6px;background:" + esc(r.brand()) + "\"></div><div style=\"padding:22px 24px;font-size:15px;line-height:1.55\">"
                + "<div style=\"font-size:18px;font-weight:bold;margin-bottom:14px\">" + esc(r.business()) + "</div>"
                + "<p>Hi " + esc(first) + ",</p><p>This is " + esc(r.business()) + ": " + esc(lead) + esc(total) + "</p>"
                + "<p style=\"background:#f6f7f3;border-radius:10px;padding:12px 14px\">" + esc(how) + "</p>"
                + "<p style=\"margin:20px 0\"><a href=\"" + esc(r.link()) + "\" style=\"background:" + esc(r.brand())
                + ";color:#fff;text-decoration:none;padding:12px 20px;border-radius:10px;font-weight:bold;display:inline-block\">See your statement</a></p>"
                + "<p style=\"color:#6b7280\">If you've already paid, thank you, and please ignore this message."
                + (r.phone() == null ? "" : " Questions? Call " + esc(r.phone()) + ".") + "</p></div></div></div>";
        return mailer.trySend(r.to(), r.name(), subject, text, html);
    }
}
