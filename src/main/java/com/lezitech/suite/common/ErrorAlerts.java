package com.lezitech.suite.common;

import com.lezitech.suite.notify.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server errors LeziTech hears about: each unexpected error is grouped with others like it (same kind, same place in
 * the code), and one email lists the groups at most every 15 minutes, so a burst of the same fault is one email,
 * not hundreds. Sent to ALERT_EMAIL (or the team email); with neither set, errors stay in the log only.
 */
@Component
public class ErrorAlerts {

    private static final Logger log = LoggerFactory.getLogger(ErrorAlerts.class);
    private static final int MAX_GROUPS = 50;

    /** One kind of error: how often, first and last time, and one example request. */
    public static final class Group {
        final String kind;
        final String where;
        final String example;
        int count;
        final Instant first;
        Instant last;

        Group(String kind, String where, String example) {
            this.kind = kind;
            this.where = where;
            this.example = example;
            this.first = Instant.now();
            this.last = first;
        }
    }

    private final Mailer mailer;
    private final String to;
    private final Map<String, Group> waiting = new LinkedHashMap<>();
    private int dropped;

    public ErrorAlerts(Mailer mailer, @Value("${suite.alert-email:}") String alertEmail) {
        this.mailer = mailer;
        this.to = alertEmail == null || alertEmail.isBlank() ? mailer.teamEmail() : alertEmail;
    }

    /** Note an unexpected error from a request (method and path, never the body or any personal data). */
    public synchronized void record(Throwable e, String request) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String where = "";
        for (StackTraceElement f : root.getStackTrace()) {
            if (f.getClassName().startsWith("com.lezitech")) {
                where = f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber();
                break;
            }
        }
        String kind = root.getClass().getSimpleName();
        String key = kind + "@" + where;
        Group g = waiting.get(key);
        if (g == null) {
            if (waiting.size() >= MAX_GROUPS) {
                dropped++;
                return;
            }
            g = new Group(kind, where, request);
            waiting.put(key, g);
        }
        g.count++;
        g.last = Instant.now();
    }

    /** The groups waiting to be sent (tests). */
    public synchronized List<Group> waiting() {
        return new ArrayList<>(waiting.values());
    }

    /** Every 15 minutes: one email listing what went wrong since the last one. Returns what was sent (tests). */
    @Scheduled(fixedDelayString = "${suite.alert-every-ms:900000}", initialDelayString = "${suite.alert-every-ms:900000}")
    public String flush() {
        List<Group> groups;
        int lost;
        synchronized (this) {
            if (waiting.isEmpty()) {
                return null;
            }
            groups = new ArrayList<>(waiting.values());
            lost = dropped;
            waiting.clear();
            dropped = 0;
        }
        int total = groups.stream().mapToInt(x -> x.count).sum();
        StringBuilder text = new StringBuilder("Busynez had " + total + " server error" + (total == 1 ? "" : "s") + " in " + groups.size()
                + " group" + (groups.size() == 1 ? "" : "s") + " since the last alert.\n\n");
        for (Group g : groups) {
            text.append(g.count).append(" × ").append(g.kind).append(" at ").append(g.where.isEmpty() ? "(outside our code)" : g.where)
                    .append("\n   first ").append(g.first).append(", last ").append(g.last)
                    .append("\n   e.g. ").append(g.example).append("\n\n");
        }
        if (lost > 0) {
            text.append(lost).append(" more error(s) of other kinds weren't grouped (too many kinds at once).\n");
        }
        text.append("Full details are in the server log at those times.");
        String subject = "Busynez: " + total + " server error" + (total == 1 ? "" : "s") + " (" + groups.get(0).kind + (groups.size() > 1 ? " and more" : "") + ")";
        if (to == null || to.isBlank()) {
            log.warn("{}\n{}", subject, text);
            return text.toString();
        }
        String html = "<pre style=\"font-family:ui-monospace,Menlo,monospace;font-size:13px\">"
                + text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";
        String status = mailer.send(to, "LeziTech", subject, text.toString(), html);
        if (!"SENT".equals(status)) {
            log.warn("Error alert not emailed ({}): {}\n{}", status, subject, text);
        }
        return text.toString();
    }
}
