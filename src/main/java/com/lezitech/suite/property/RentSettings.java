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
        return out;
    }

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
        if (in.get("payInstructions") instanceof String s) {
            if (s.length() > 200) {
                throw ApiException.bad("Keep the payment instructions to 200 characters");
            }
            out.put("payInstructions", s.trim());
        }
        return out;
    }
}
