package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Package H: float before the first sale, blind count, cashiers' own tills, and checking (locking) till days. */
class TillsTest extends IntegrationTest {

    private static Map<String, Object> cash(int amount) {
        return Map.of("method", "CASH", "amount", amount);
    }

    @Test
    void aCashierOpensTheirTillWithAFloatBeforeTakingMoney() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        String sugar = item(owner, "Sugar").get("id").asText();

        Res refused = post("/orders", cashier, Map.of("lines", line(sugar, 1), "payments", List.of(cash(190))));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.at("/code").asText()).isEqualTo("TILL_CLOSED");
        // A bill can still be started (e.g. a table's order); only taking money needs the till.
        assertThat(post("/orders", cashier, Map.of("lines", line(sugar, 1))).status()).isEqualTo(200);

        assertThat(post("/shifts/open", cashier, Map.of()).status()).as("the float must be entered").isEqualTo(400);
        assertThat(post("/shifts/open", cashier, Map.of("openingCash", -5)).status()).isEqualTo(400);
        assertThat(post("/shifts/open", cashier, Map.of("openingCash", 1000)).status()).isEqualTo(200);
        assertThat(post("/orders", cashier, Map.of("lines", line(sugar, 1), "payments", List.of(cash(190)))).status()).isEqualTo(200);

        // Owners and managers can take a payment without a till of their own.
        assertThat(post("/orders", owner, Map.of("lines", line(sugar, 1), "payments", List.of(cash(190)))).status()).isEqualTo(200);
        // So can a waiter once they open theirs (they couldn't open one before).
        String waiter = staff(owner, "WAITER");
        assertThat(post("/shifts/open", waiter, Map.of("openingCash", 0)).status()).isEqualTo(200);
    }

    @Test
    void cashiersCountBlindAndOnlySeeTheirOwnTills() {
        String owner = token(signup("retail"));
        String manager = staff(owner, "MANAGER");
        String cashier = staff(owner, "CASHIER");
        String other = staff(owner, "CASHIER");
        String sugar = item(owner, "Sugar").get("id").asText();

        JsonNode open = post("/shifts/open", cashier, Map.of("openingCash", 1000)).body();
        assertThat(open.has("expectedCash")).isFalse();
        String shift = open.get("id").asText();
        post("/orders", cashier, Map.of("lines", line(sugar, 2), "payments", List.of(cash(380))));

        JsonNode mine = get("/shifts/current", cashier).body();
        assertThat(mine.get("blind").asBoolean()).isTrue();
        assertThat(mine.has("expectedCash")).isFalse();
        assertThat(mine.has("byMethod")).isFalse();
        assertThat(mine.get("sales").asDouble()).isEqualTo(380);

        // Another cashier can't look at it; a manager sees everything.
        post("/shifts/open", other, Map.of("openingCash", 0));
        assertThat(get("/shifts/" + shift, other).status()).isEqualTo(403);
        assertThat(post("/shifts/" + shift + "/close", other, Map.of("countedCash", 0)).status()).isEqualTo(403);
        assertThat(get("/shifts/" + shift, manager).at("/expectedCash").asDouble()).isEqualTo(1380);

        // Closing needs the cash count, and the reply doesn't give the expected amount away.
        assertThat(post("/shifts/" + shift + "/close", cashier, Map.of()).status()).isEqualTo(400);
        JsonNode closed = post("/shifts/" + shift + "/close", cashier, Map.of("countedCash", 1350)).body();
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.has("expectedCash")).isFalse();
        assertThat(closed.has("cashDifference")).isFalse();
        assertThat(get("/shifts/" + shift, manager).at("/cashDifference").asDouble()).isEqualTo(-30);

        // A cashier's list is their own tills only.
        for (JsonNode s : get("/shifts", cashier).body()) {
            assertThat(s.get("userName").asText()).isEqualTo(get("/shifts/" + shift, manager).at("/userName").asText());
        }
        assertThat(get("/shifts", manager).body().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void checkingATillDayLocksItsSalesUntilTheOwnerUnlocks() {
        String owner = token(signup("retail"));
        String manager = staff(owner, "MANAGER");
        String cashier = staff(owner, "CASHIER");
        String sugar = item(owner, "Sugar").get("id").asText();

        String shift = post("/shifts/open", cashier, Map.of("openingCash", 500)).at("/id").asText();
        String sale = post("/orders", cashier, Map.of("lines", line(sugar, 1), "payments", List.of(cash(190)))).at("/id").asText();

        // Only an open till can't be checked; cashiers can't check at all.
        assertThat(post("/shifts/" + shift + "/reconcile", manager, Map.of("status", "MATCHED")).status()).isEqualTo(409);
        post("/shifts/" + shift + "/close", cashier, Map.of("countedCash", 650));
        assertThat(post("/shifts/" + shift + "/reconcile", cashier, Map.of("status", "MATCHED")).status()).isEqualTo(403);

        JsonNode days = get("/shifts/days", manager).body();
        assertThat(days.get("toCheck").asInt()).isEqualTo(1);
        JsonNode row = days.get("shifts").get(0);
        assertThat(row.get("cashDifference").asDouble()).isEqualTo(-40);

        // A difference needs a note.
        assertThat(post("/shifts/" + shift + "/reconcile", manager, Map.of("status", "DIFFERENCE")).status()).isEqualTo(400);
        JsonNode checked = post("/shifts/" + shift + "/reconcile", manager, Map.of("status", "DIFFERENCE", "note", "Change given twice")).body();
        assertThat(checked.get("locked").asBoolean()).isTrue();
        assertThat(checked.get("reconcileStatus").asText()).isEqualTo("DIFFERENCE");
        assertThat(get("/shifts/days", manager).at("/toCheck").asInt()).isZero();
        assertThat(post("/shifts/" + shift + "/reconcile", manager, Map.of("status", "MATCHED")).status()).as("already locked").isEqualTo(409);

        // Its sales can't be refunded or cancelled now.
        Res refund = post("/orders/" + sale + "/refund", owner, Map.of("reason", "Returned"));
        assertThat(refund.status()).isEqualTo(409);
        assertThat(refund.at("/code").asText()).isEqualTo("TILL_LOCKED");
        assertThat(post("/orders/" + sale + "/void", owner, Map.of("reason", "Mistake")).status()).isEqualTo(409);

        // The cashier sees it was checked, but not how.
        JsonNode theirs = get("/shifts/" + shift, cashier).body();
        assertThat(theirs.get("checked").asBoolean()).isTrue();
        assertThat(theirs.has("reconcileStatus")).isFalse();

        // Only the owner unlocks, with a reason, and it's in the activity log.
        assertThat(post("/shifts/" + shift + "/unlock", manager, Map.of("reason", "x")).status()).isEqualTo(403);
        assertThat(post("/shifts/" + shift + "/unlock", owner, Map.of()).status()).isEqualTo(400);
        assertThat(post("/shifts/" + shift + "/unlock", owner, Map.of("reason", "Customer returned the sugar")).at("/locked").asBoolean()).isFalse();
        assertThat(get("/activity", owner).body().toString()).contains("TILL_UNLOCKED").contains("TILL_CHECKED");
        assertThat(post("/orders/" + sale + "/refund", owner, Map.of("reason", "Returned")).status()).isEqualTo(200);
    }
}
