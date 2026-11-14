package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A waiter clears at the end of their shift: hands over the cash they took; bills still unpaid and any cash short are
 * recorded against them; the cashier or a manager confirms; the owner settles what's owed (paid back, salary, written off).
 */
class WaiterClearingTest extends IntegrationTest {

    @Test
    void aWaiterClearsWithTheCashierAndWhatIsMissingIsRecordedAgainstThem() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String waiter = staff(owner, "WAITER");
        String cashier = staff(owner, "CASHIER");
        String waiterId = get("/auth/me", waiter).at("/user/id").asText();
        String dish = null;
        double price = 0;
        for (JsonNode i : get("/items", owner).body()) {
            if (dish == null && "MENU".equals(i.get("kind").asText()) && i.get("options").isEmpty()) {
                dish = i.get("id").asText();
                price = i.get("price").asDouble();
            }
        }
        post("/shifts/open", waiter, Map.of("openingCash", 0));
        List<Map<String, Object>> line = List.of(Map.of("itemId", dish, "qty", 1));

        // Three bills: paid in cash, paid by M-Pesa, and one left unpaid (the customer walked out).
        String cashBill = post("/orders", waiter, Map.of("lines", line)).at("/id").asText();
        assertThat(post("/orders/" + cashBill + "/pay", waiter, Map.of("payments", List.of(Map.of("method", "CASH", "amount", price)))).status()).isEqualTo(200);
        String mpesaBill = post("/orders", waiter, Map.of("lines", line)).at("/id").asText();
        post("/orders/" + mpesaBill + "/pay", waiter, Map.of("payments", List.of(Map.of("method", "MPESA", "amount", price, "reference", "QX12AB34CD"))));
        String walkedOut = post("/orders", waiter, Map.of("lines", line)).at("/id").asText();

        // The waiter sees their shift: 3 bills, cash to hand over, M-Pesa already in, one unpaid.
        JsonNode mine = get("/waiter-clearing/me", waiter).body();
        assertThat(mine.at("/bills")).hasSize(3);
        assertThat(mine.at("/cashToHandOver").asDouble()).isEqualTo(price);
        assertThat(mine.at("/mpesa").asDouble()).isEqualTo(price);
        assertThat(mine.at("/unpaidTotal").asDouble()).isEqualTo(price);

        // Someone else must count the cash: the waiter can't clear themselves.
        assertThat(post("/waiter-clearing/" + waiterId + "/clear", waiter, Map.of("cashReceived", price)).status()).isEqualTo(403);
        assertThat(get("/waiter-clearing/waiting", cashier).body().toString()).contains(waiterId);

        // The cashier counts KES 100 less than expected and clears: short 100 and the unpaid bill are owed by the waiter.
        Res cleared = post("/waiter-clearing/" + waiterId + "/clear", cashier, Map.of("cashReceived", price - 100, "note", "Counted at the counter"));
        assertThat(cleared.status()).as(cleared.body().toString()).isEqualTo(200);
        assertThat(cleared.at("/short").asDouble()).isEqualTo(100);
        assertThat(cleared.at("/owedNow").asDouble()).isEqualTo(price + 100);
        assertThat(get("/orders/" + walkedOut, owner).at("/status").asText()).isNotEqualTo("OPEN");

        // A new shift starts empty.
        assertThat(get("/waiter-clearing/me", waiter).at("/bills")).isEmpty();

        // The owner sees what's owed; the waiter sees their own.
        JsonNode owed = get("/waiter-clearing/owed", owner).body();
        assertThat(owed.at("/staff/0/owed").asDouble()).isEqualTo(price + 100);
        assertThat(owed.at("/clearings/0/short").asDouble()).isEqualTo(100);
        assertThat(get("/waiter-clearing/owed", waiter).at("/entries")).hasSize(2);

        // The shortage comes off the salary; paying back more than is owed is refused; the waiter can't settle.
        assertThat(post("/waiter-clearing/owed/" + waiterId + "/settle", waiter, Map.of("amount", 100, "how", "SALARY")).status()).isEqualTo(403);
        assertThat(post("/waiter-clearing/owed/" + waiterId + "/settle", owner, Map.of("amount", 100, "how", "SALARY", "note", "November pay")).at("/owed").asDouble())
                .isEqualTo(price);
        assertThat(post("/waiter-clearing/owed/" + waiterId + "/settle", owner, Map.of("amount", price + 1, "how", "PAID_BACK")).status()).isEqualTo(400);
        assertThat(post("/waiter-clearing/owed/" + waiterId + "/settle", owner, Map.of("amount", price, "how", "PAID_BACK")).at("/owed").asDouble()).isZero();
    }
}
