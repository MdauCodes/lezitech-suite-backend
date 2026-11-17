package com.lezitech.suite.rooms;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Reading and writing the small part of iCalendar that Airbnb and Booking.com use: all-day events with start and end dates. */
public final class Ical {

    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;

    private Ical() {
    }

    public record Event(String uid, LocalDate start, LocalDate end, String summary) {
    }

    /** VEVENTs with a start date; an event with no end (or end on the start) lasts one night. Bad events are skipped. */
    public static List<Event> parse(String text) {
        List<String> lines = unfold(text);
        List<Event> out = new ArrayList<>();
        String uid = null;
        String summary = null;
        LocalDate start = null;
        LocalDate end = null;
        boolean in = false;
        for (String line : lines) {
            String upper = line.toUpperCase();
            if (upper.equals("BEGIN:VEVENT")) {
                in = true;
                uid = summary = null;
                start = end = null;
            } else if (upper.equals("END:VEVENT")) {
                if (in && start != null) {
                    LocalDate e = end == null || !end.isAfter(start) ? start.plusDays(1) : end;
                    out.add(new Event(uid, start, e, summary));
                }
                in = false;
            } else if (in) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = upper.substring(0, colon).split(";")[0];
                String value = line.substring(colon + 1).trim();
                switch (name) {
                    case "UID" -> uid = clip(value, 300);
                    case "SUMMARY" -> summary = clip(value.replace("\\,", ",").replace("\\n", " "), 200);
                    case "DTSTART" -> start = date(value);
                    case "DTEND" -> end = date(value);
                    default -> { }
                }
            }
        }
        return out;
    }

    private static List<String> unfold(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    /** 20261120 or 20261120T140000Z: the date part. */
    static LocalDate date(String v) {
        try {
            return LocalDate.parse(v.length() >= 8 ? v.substring(0, 8) : v, D);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** A calendar of booked nights for other sites to read. No guest names: just "Booked". */
    public static String write(String calendarName, List<Event> events) {
        StringBuilder b = new StringBuilder();
        line(b, "BEGIN:VCALENDAR");
        line(b, "VERSION:2.0");
        line(b, "PRODID:-//BizNez//Rooms//EN");
        line(b, "CALSCALE:GREGORIAN");
        line(b, "X-WR-CALNAME:" + escape(calendarName));
        String stamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        for (Event e : events) {
            line(b, "BEGIN:VEVENT");
            line(b, "UID:" + e.uid());
            line(b, "DTSTAMP:" + stamp);
            line(b, "DTSTART;VALUE=DATE:" + e.start().format(D));
            line(b, "DTEND;VALUE=DATE:" + e.end().format(D));
            line(b, "SUMMARY:" + escape(e.summary() == null ? "Booked" : e.summary()));
            line(b, "END:VEVENT");
        }
        line(b, "END:VCALENDAR");
        return b.toString();
    }

    private static void line(StringBuilder b, String s) {
        b.append(s).append("\r\n");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", " ");
    }
}
