package com.lezitech.suite.property;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A landlord's rent choices, kept in {@code settings.rent}:
 * <ul>
 *   <li>{@code autoInvoice}: raise each month's invoices by themselves on the 1st (default on)</li>
 *   <li>{@code emailReminders}: email tenants about rent (default on; only tenants with an email get them)</li>
 *   <li>{@code daysBefore}: remind this many days before the due day (default 3; 0 = no early reminder)</li>
 *   <li>{@code onDueDay}: remind on the due day (default on)</li>
 *   <li>{@code daysAfter}: remind this many days after it, if still unpaid (default 3 and 10)</li>
 *   <li>{@code payInstructions}: how to pay, in the landlord's words, e.g. "Paybill 522522, account: your unit number"</li>
 *   <li>{@code utilities}: per kind (WATER, POWER): {@code charge} (bill tenants for it), {@code price} per unit used,
 *       {@code fixedFee} a month (e.g. meter rent), {@code unit} (m³, kWh). A lease can switch a kind off.</li>
 *   <li>{@code serviceMode}: FIXED (an amount on each lease), BUDGET (the year's budget shared by floor size) or
 *       INCLUDED (in the rent; no service-charge line)</li>
 * </ul>
 * SMS and WhatsApp reminders are set up by LeziTech for a business on request (quoted separately).
 */
public final class RentSettings {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RentSettings() {
    }

    public static Map<String, Object> withDefaults(String savedJson) {
        Map<String, Object> in;
        try {
            in = savedJson == null ? Map.of() : JSON.readValue(savedJson, Map.class);
        } catch (Exception e) {
            in = Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("autoInvoice", in.get("autoInvoice") instanceof Boolean b ? b : true);
        out.put("emailReminders", in.get("emailReminders") instanceof Boolean b ? b : true);
        out.put("daysBefore", in.get("daysBefore") instanceof Number n ? n.intValue() : 3);
        out.put("onDueDay", in.get("onDueDay") instanceof Boolean b ? b : true);
        out.put("daysAfter", in.get("daysAfter") instanceof List<?> l ? l : List.of(3, 10));
        out.put("payInstructions", in.get("payInstructions") instanceof String s ? s : "");
        Map<String, Object> utilities = new LinkedHashMap<>();
        Map<?, ?> savedU = in.get("utilities") instanceof Map<?, ?> m ? m : Map.of();
        for (String kind : KINDS) {
            Map<?, ?> u = savedU.get(kind) instanceof Map<?, ?> m ? m : Map.of();
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("charge", u.get("charge") instanceof Boolean b && b);
            k.put("price", u.get("price") instanceof Number n ? n : 0);
            k.put("fixedFee", u.get("fixedFee") instanceof Number n ? n : 0);
            k.put("unit", u.get("unit") instanceof String str && !str.isBlank() ? str : "WATER".equals(kind) ? "m³" : "kWh");
            utilities.put(kind, k);
        }
        out.put("utilities", utilities);
        out.put("serviceMode", in.get("serviceMode") instanceof String m && MODES.contains(m) ? m : "FIXED");
        Map<?, ?> mk = in.get("market") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("openDays", mk.get("openDays") instanceof List<?> l ? l : List.of(1, 2, 3, 4, 5, 6, 7));
        market.put("casualTickets", mk.get("casualTickets") instanceof Boolean b && b);
        market.put("ticketFee", mk.get("ticketFee") instanceof Number n ? n : 50);
        out.put("market", market);
        // Residential rental income tax (MRI): a % of residential rent received, filed monthly by the 20th. Editable;
        // the default is the rate in force at writing (confirm with an accountant).
        out.put("mriRate", in.get("mriRate") instanceof Number n ? n : 7.5);
        // What the service charge pays for (security, cleaning...), in the landlord's words; tenants see it.
        out.put("serviceIncludes", in.get("serviceIncludes") instanceof List<?> l ? l : List.of());
        return out;
    }

    public static final List<String> KINDS = List.of("WATER", "POWER");
    public static final List<String> MODES = List.of("FIXED", "BUDGET", "INCLUDED");

    public static Map<String, Object> clean(Map<String, Object> in) {
        if (in == null) {
            throw ApiException.bad("Rent settings are missing");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (in.get("autoInvoice") instanceof Boolean b) {
            out.put("autoInvoice", b);
        }
        if (in.get("emailReminders") instanceof Boolean b) {
            out.put("emailReminders", b);
        }
        if (in.get("onDueDay") instanceof Boolean b) {
            out.put("onDueDay", b);
        }
        if (in.containsKey("daysBefore")) {
            int d = in.get("daysBefore") instanceof Number n ? n.intValue() : -1;
            if (d < 0 || d > 14) {
                throw ApiException.bad("Remind between 0 and 14 days before the due day");
            }
            out.put("daysBefore", d);
        }
        if (in.get("daysAfter") instanceof List<?> l) {
            TreeSet<Integer> days = new TreeSet<>();
            for (Object o : l) {
                int d = o instanceof Number n ? n.intValue() : -1;
                if (d < 1 || d > 90) {
                    throw ApiException.bad("Reminders after the due day must be between 1 and 90 days");
                }
                days.add(d);
            }
            if (days.size() > 4) {
                throw ApiException.bad("Up to 4 reminders after the due day");
            }
            out.put("daysAfter", new ArrayList<>(days));
        }
        if (in.containsKey("serviceMode")) {
            String m = String.valueOf(in.get("serviceMode")).toUpperCase();
            if (!MODES.contains(m)) {
                throw ApiException.bad("Choose how service charge works: a fixed amount per lease, a yearly budget, or included in the rent");
            }
            out.put("serviceMode", m);
        }
        if (in.get("utilities") instanceof Map<?, ?> u) {
            Map<String, Object> utilities = new LinkedHashMap<>();
            for (String kind : KINDS) {
                if (!(u.get(kind) instanceof Map<?, ?> k)) {
                    continue;
                }
                double price = k.get("price") instanceof Number n ? n.doubleValue() : 0;
                double fee = k.get("fixedFee") instanceof Number n ? n.doubleValue() : 0;
                if (price < 0 || fee < 0 || price > 100_000 || fee > 1_000_000) {
                    throw ApiException.bad("Check the water and electricity prices");
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("charge", k.get("charge") instanceof Boolean b && b);
                c.put("price", price);
                c.put("fixedFee", fee);
                String unit = k.get("unit") instanceof String str ? str.trim() : "";
                c.put("unit", unit.isEmpty() ? ("WATER".equals(kind) ? "m³" : "kWh") : unit.length() > 10 ? unit.substring(0, 10) : unit);
                utilities.put(kind, c);
            }
            out.put("utilities", utilities);
        }
        if (in.get("market") instanceof Map<?, ?> mk) {
            Map<String, Object> market = new LinkedHashMap<>();
            TreeSet<Integer> days = new TreeSet<>();
            if (mk.get("openDays") instanceof List<?> l) {
                for (Object o : l) {
                    int d = o instanceof Number n ? n.intValue() : 0;
                    if (d < 1 || d > 7) {
                        throw ApiException.bad("Open days are Monday (1) to Sunday (7)");
                    }
                    days.add(d);
                }
            }
            if (days.isEmpty()) {
                throw ApiException.bad("Choose at least one day the market opens");
            }
            market.put("openDays", new ArrayList<>(days));
            market.put("casualTickets", mk.get("casualTickets") instanceof Boolean b && b);
            double fee = mk.get("ticketFee") instanceof Number n ? n.doubleValue() : 50;
            if (fee < 0 || fee > 100_000) {
                throw ApiException.bad("Check the ticket fee");
            }
            market.put("ticketFee", fee);
            out.put("market", market);
        }
        if (in.get("serviceIncludes") instanceof List<?> l) {
            List<String> items = new ArrayList<>();
            for (Object o : l) {
                String s = o == null ? "" : String.valueOf(o).trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.length() > 60) {
                    throw ApiException.bad("Keep each service to a few words");
                }
                items.add(s);
            }
            if (items.size() > 15) {
                throw ApiException.bad("Up to 15 services");
            }
            out.put("serviceIncludes", items);
        }
        if (in.containsKey("mriRate")) {
            double r = in.get("mriRate") instanceof Number n ? n.doubleValue() : -1;
            if (r < 0 || r > 50) {
                throw ApiException.bad("Check the rental income tax rate (a percentage)");
            }
            out.put("mriRate", r);
        }
        if (in.get("payInstructions") instanceof String s) {
            if (s.length() > 200) {
                throw ApiException.bad("Keep the payment instructions to 200 characters");
            }
            out.put("payInstructions", s.trim());
        }
        return out;
    }
}
