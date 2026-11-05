package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** M-Pesa prompts and till payments, using the built-in simulator in place of Safaricom. */
class MpesaTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode waitFor(String requestId, String token) throws InterruptedException {
        JsonNode s = null;
        for (int i = 0; i < 50; i++) {
            s = get("/mpesa/requests/" + requestId, token).body();
            if (!"PENDING".equals(s.get("status").asText())) {
                return s;
            }
            Thread.sleep(100);
        }
        return s;
    }

    private String openSale(String token, String itemId) {
        Res r = post("/orders", token, Map.of("lines", line(itemId, 1)));
        assertThat(r.status()).isEqualTo(200);
        return r.at("/id").asText();
    }

    /** payments has row-level security, so count through the API as the workspace sees it. */
    private int payments(String token, String orderId) {
        return get("/orders/" + orderId, token).at("/payments").size();
    }

    @Test
    void promptPaysTheSaleExactlyOnceEvenIfSafaricomCallsTwice() throws Exception {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        String order = openSale(cashier, item(owner, "Sugar").get("id").asText());

        Res start = post("/mpesa/stk", cashier, Map.of("orderId", order, "phone", "0712 345 678"));
        assertThat(start.status()).isEqualTo(200);
        assertThat(start.at("/status").asText()).isEqualTo("PENDING");
        assertThat(start.at("/phone").asText()).isEqualTo("254712345678");

        JsonNode done = waitFor(start.at("/id").asText(), cashier);
        assertThat(done.get("status").asText()).isEqualTo("PAID");
        assertThat(done.get("receipt").asText()).startsWith("SIM");
        assertThat(get("/orders/" + order, cashier).at("/status").asText()).isEqualTo("PAID");
        assertThat(payments(cashier, order)).isEqualTo(1);

        // Safaricom retries the same callback: nothing new is recorded.
        Map<String, Object> r = jdbc.queryForMap("SELECT callback_token, checkout_request_id FROM mpesa_requests WHERE id = ?::uuid", start.at("/id").asText());
        Map<String, Object> cb = Map.of("Body", Map.of("stkCallback", Map.of("CheckoutRequestID", r.get("checkout_request_id"), "ResultCode", 0,
                "CallbackMetadata", Map.of("Item", List.of(Map.of("Name", "Amount", "Value", 190), Map.of("Name", "MpesaReceiptNumber", "Value", "AGAIN1"))))));
        assertThat(post("/public/mpesa/stk/" + r.get("callback_token"), null, cb).status()).isEqualTo(200);
        assertThat(payments(cashier, order)).isEqualTo(1);
    }

    @Test
    void customerCancellingLeavesTheSaleOpen() throws Exception {
        String owner = token(signup("retail"));
        String order = openSale(owner, item(owner, "Sugar").get("id").asText());
        Res start = post("/mpesa/stk", owner, Map.of("orderId", order, "phone", "0712349999"));
        assertThat(waitFor(start.at("/id").asText(), owner).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(get("/orders/" + order, owner).at("/status").asText()).isEqualTo("OPEN");
        assertThat(payments(owner, order)).isZero();
    }

    @Test
    void madeUpCallbacksAreIgnored() {
        String owner = token(signup("retail"));
        String order = openSale(owner, item(owner, "Sugar").get("id").asText());
        Res start = post("/mpesa/stk", owner, Map.of("orderId", order, "phone", "0712345678"));
        String token = jdbc.queryForObject("SELECT callback_token FROM mpesa_requests WHERE id = ?::uuid", String.class, start.at("/id").asText());
        Map<String, Object> forged = Map.of("Body", Map.of("stkCallback", Map.of("CheckoutRequestID", "ws_CO_FAKE", "ResultCode", 0,
                "CallbackMetadata", Map.of("Item", List.of(Map.of("Name", "Amount", "Value", 999999), Map.of("Name", "MpesaReceiptNumber", "Value", "FAKE"))))));
        // Wrong token, then right token with someone else's checkout id.
        post("/public/mpesa/stk/not-a-real-token", null, forged);
        post("/public/mpesa/stk/" + token, null, forged);
        String receipt = jdbc.queryForObject("SELECT coalesce(receipt, '') FROM mpesa_requests WHERE id = ?::uuid", String.class, start.at("/id").asText());
        assertThat(receipt).isNotEqualTo("FAKE");
        // Asking for more than is due is refused up front.
        assertThat(post("/mpesa/stk", owner, Map.of("orderId", order, "phone", "0712345678", "amount", 999999)).status()).isEqualTo(400);
        assertThat(post("/mpesa/stk", owner, Map.of("orderId", order, "phone", "12345")).status()).isEqualTo(400);
    }

    @Test
    void payingForAPlanByMpesaSwitchesItOn() throws Exception {
        JsonNode session = signup("retail");
        String owner = token(session);
        Res start = post("/billing/mpesa", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY", "phone", "0722000111"));
        assertThat(start.status()).isEqualTo(200);
        assertThat(start.at("/amount").decimalValue()).isEqualByComparingTo("4500");
        assertThat(waitFor(start.at("/id").asText(), owner).get("status").asText()).isEqualTo("PAID");
        Map<String, Object> t = jdbc.queryForMap("SELECT t.status, t.plan, r.paid_via FROM tenants t JOIN subscription_requests r ON r.tenant_id = t.id WHERE r.id = ?::uuid",
                start.at("/subscriptionRequestId").asText());
        assertThat(t.get("status")).isEqualTo("ACTIVE");
        assertThat(t.get("plan")).isEqualTo("GROWTH");
        assertThat(t.get("paid_via")).isEqualTo("MPESA");
        // Only the owner can buy a plan.
        String cashier = staff(owner, "CASHIER");
        assertThat(post("/billing/mpesa", cashier, Map.of("plan", "PRO", "phone", "0722000111")).status()).isEqualTo(403);
    }

    @Test
    void tillPaymentsMatchByOrderNumberOrWaitForStaff() {
        String owner = token(signup("retail"));
        String till = String.valueOf(600000 + (int) (Math.random() * 99999));
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("mpesaTill", till))).status()).isEqualTo(200);
        String sugar = item(owner, "Sugar").get("id").asText();
        String order = openSale(owner, sugar);
        int number = get("/orders/" + order, owner).at("/number").asInt();
        String total = get("/orders/" + order, owner).at("/total").asText();

        // Wrong secret in the URL: refused.
        assertThat(post("/public/mpesa/c2b/wrong/confirmation", null, Map.of("TransID", "X1")).status()).isEqualTo(404);

        // Customer typed the sale number as the account: it pays the sale.
        String trans = "TC" + System.nanoTime();
        post("/public/mpesa/c2b/test-c2b-secret/confirmation", null, Map.of("TransID", trans, "TransAmount", total,
                "BusinessShortCode", till, "BillRefNumber", "#" + number, "MSISDN", "254712345678", "FirstName", "Jane"));
        assertThat(get("/orders/" + order, owner).at("/status").asText()).isEqualTo("PAID");
        // Delivered twice: still one payment.
        post("/public/mpesa/c2b/test-c2b-secret/confirmation", null, Map.of("TransID", trans, "TransAmount", total,
                "BusinessShortCode", till, "BillRefNumber", "#" + number));
        assertThat(payments(owner, order)).isEqualTo(1);

        // No usable account number: it waits in the list and staff put it on a sale.
        String order2 = openSale(owner, sugar);
        post("/public/mpesa/c2b/test-c2b-secret/confirmation", null, Map.of("TransID", "TD" + System.nanoTime(), "TransAmount", total,
                "BusinessShortCode", till, "BillRefNumber", "shop"));
        JsonNode waiting = get("/mpesa/till-payments", owner).body();
        assertThat(waiting).hasSize(1);
        Res applied = post("/mpesa/till-payments/" + waiting.get(0).get("id").asText() + "/apply", owner, Map.of("orderId", order2));
        assertThat(applied.status()).isEqualTo(200);
        assertThat(get("/orders/" + order2, owner).at("/status").asText()).isEqualTo("PAID");
        assertThat(get("/mpesa/till-payments", owner).body()).isEmpty();
        // Another workspace can't see it.
        String other = token(signup("retail"));
        assertThat(get("/mpesa/till-payments?status=ALL", other).body()).isEmpty();
    }
}
