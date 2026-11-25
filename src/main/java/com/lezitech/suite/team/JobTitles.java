package com.lezitech.suite.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The job titles a business starts with, by kind of business: each with what the person does there and the access
 * level it works like. The owner can rename them, describe them their way, or add their own.
 */
public final class JobTitles {

    private JobTitles() {
    }

    public record Job(String title, String role, String description) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("role", role);
            m.put("description", description);
            return m;
        }
    }

    private static final List<Job> COMMON_TOP = List.of(
            new Job("Director", "OWNER", "Runs the business. Sees and changes everything, including the plan, payments and who can do what."),
            new Job("Operations manager", "MANAGER", "Runs the day: staff, stock, prices, refunds and reports. Owner things (plan, payments) only if the director allows."));

    private static final Map<String, List<Job>> BY_SECTOR = Map.of(
            "retail", List.of(
                    new Job("Cashier", "CASHIER", "Sells at the till, takes cash and M-Pesa, prints receipts."),
                    new Job("Store keeper", "STOREKEEPER", "Receives deliveries, counts stock, moves stock between the store and shops."),
                    new Job("Accountant", "ACCOUNTANT", "Checks sales, payments, money owed and reports; doesn't sell.")),
            "wholesale", List.of(
                    new Job("Sales person", "SALES", "Follows up buyers, sends quotes and takes orders."),
                    new Job("Cashier", "CASHIER", "Takes payments and prints invoices and receipts."),
                    new Job("Store keeper", "STOREKEEPER", "Receives deliveries, counts stock, prepares orders for collection."),
                    new Job("Driver", "RIDER", "Accepts the load, delivers each stop and gets the client to confirm what arrived."),
                    new Job("Accountant", "ACCOUNTANT", "Checks sales, payments, money owed and reports; doesn't sell.")),
            "pharmacy", List.of(
                    new Job("Pharmacist", "MANAGER", "Dispenses, checks prescriptions and expiry dates, manages stock."),
                    new Job("Cashier", "CASHIER", "Sells at the till, takes cash and M-Pesa."),
                    new Job("Store keeper", "STOREKEEPER", "Receives deliveries, counts stock, watches expiry dates.")),
            "restaurant", List.of(
                    new Job("Waiter", "WAITER", "Takes orders at the tables, sends them to the kitchen, serves and brings the bill."),
                    new Job("Cashier", "CASHIER", "Takes payments at the counter and for takeaways."),
                    new Job("Chef", "KITCHEN", "Sees orders on the kitchen screen and marks them ready."),
                    new Job("Rider", "RIDER", "Delivers orders and brings back the cash taken at the door."),
                    new Job("Store keeper", "STOREKEEPER", "Receives deliveries, counts stock and ingredients.")),
            "hotel", List.of(
                    new Job("Receptionist", "RECEPTION", "Checks guests in and out and takes their payments."),
                    new Job("Waiter", "WAITER", "Takes orders in the restaurant and bar."),
                    new Job("Chef", "KITCHEN", "Sees orders on the kitchen screen and marks them ready."),
                    new Job("Accountant", "ACCOUNTANT", "Checks sales, payments and reports.")),
            "beauty", List.of(
                    new Job("Stylist", "STYLIST", "Sees their bookings and the services done; earns commission."),
                    new Job("Receptionist", "CASHIER", "Books clients and takes payments.")),
            "services", List.of(
                    new Job("Sales person", "SALES", "Follows up leads and sends quotes."),
                    new Job("Cashier", "CASHIER", "Takes payments and prints invoices."),
                    new Job("Accountant", "ACCOUNTANT", "Checks invoices, payments and reports.")),
            "tours", List.of(
                    new Job("Sales person", "SALES", "Books travellers, takes deposits and follows up leads."),
                    new Job("Accountant", "ACCOUNTANT", "Checks payments and reports.")),
            "property", List.of(
                    new Job("Accountant", "ACCOUNTANT", "Checks rent paid, money owed and landlord statements."),
                    new Job("Caretaker", "TECHNICIAN", "Sees the repair jobs given to them and reads meters."),
                    new Job("Stall attendant", "COLLECTOR", "Collects daily stall fees on a phone and hands in the cash.")),
            "resort", List.of(
                    new Job("Receptionist", "RECEPTION", "Checks guests in and out and takes their payments."),
                    new Job("Waiter / barman", "WAITER", "Takes orders in the restaurant and at the bar, serves and brings the bill."),
                    new Job("Chef", "KITCHEN", "Sees orders on the kitchen screen and marks them ready."),
                    new Job("Pool or games attendant", "CASHIER", "Sells entry, time and drinks at the pool or games room."),
                    new Job("Store keeper", "STOREKEEPER", "Receives deliveries and counts stock for every area."),
                    new Job("Accountant", "ACCOUNTANT", "Checks sales, payments, money owed and reports; doesn't sell.")));

    public static List<Map<String, Object>> presets(String sector) {
        List<Map<String, Object>> out = new ArrayList<>();
        COMMON_TOP.forEach(j -> out.add(j.toMap()));
        BY_SECTOR.getOrDefault(sector, BY_SECTOR.get("retail")).forEach(j -> out.add(j.toMap()));
        return out;
    }
}
