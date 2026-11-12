package com.lezitech.suite;

import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the app hides from a role, the API also refuses. */
class RoleChecksTest extends IntegrationTest {

    @Test
    void eachJobOnlyReachesItsOwnScreens() {
        String owner = token(signup("restaurant"));
        String waiter = staff(owner, "WAITER");
        String kitchen = staff(owner, "KITCHEN");
        String cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0)); // cashiers open their till before taking money
        String sugarLike = item(owner, "Soda").get("id").asText();

        // Kitchen staff can't take orders or see money.
        assertThat(post("/orders", kitchen, Map.of("lines", line(sugarLike, 1))).status()).isEqualTo(403);
        assertThat(get("/reports/sales", kitchen).status()).isEqualTo(403);
        // ...but can move tickets along.
        String order = post("/orders", waiter, Map.of("lines", line(sugarLike, 1))).at("/id").asText();
        assertThat(post("/kitchen/orders/" + order + "/bump", kitchen, Map.of()).status()).isEqualTo(200);

        // Waiters can't see profit reports, change prices, stock, staff or settings.
        assertThat(get("/reports/sales", waiter).status()).isEqualTo(403);
        assertThat(call("PUT", "/items/" + sugarLike, waiter, Map.of("name", "x", "price", 1)).status()).isEqualTo(403);
        assertThat(post("/stock/adjust", waiter, Map.of("itemId", sugarLike, "counted", 0)).status()).isEqualTo(403);
        assertThat(get("/stock", waiter).status()).isEqualTo(403);
        assertThat(post("/team", waiter, Map.of("name", "x", "email", "x@x.test", "password", "password123", "role", "OWNER")).status()).isEqualTo(403);
        assertThat(get("/team", waiter).status()).isEqualTo(403);
        assertThat(call("PUT", "/settings", waiter, Map.of("name", "Hacked")).status()).isEqualTo(403);
        assertThat(post("/suppliers", waiter, Map.of("name", "x")).status()).isEqualTo(403);
        // ...but can see who can approve a discount.
        assertThat(get("/team/approvers", waiter).status()).isEqualTo(200);

        // Cashiers can't open the sales-leads pipeline or the till of rooms; they can sell.
        assertThat(get("/pipeline", cashier).status()).isEqualTo(403);
        assertThat(post("/orders", cashier, Map.of("lines", line(sugarLike, 1), "payments", List.of(Map.of("method", "CASH", "amount", 200)))).status())
                .isEqualTo(200);
        assertThat(get("/billing/plans", cashier).status()).isEqualTo(403);

        // The owner can do all of it.
        assertThat(get("/reports/sales", owner).status()).isEqualTo(200);
        assertThat(get("/stock", owner).status()).isEqualTo(200);
    }

    @Test
    void salesRepAndRentCollectorStayInTheirLanes() {
        String owner = token(signup("property"));
        String accountant = staff(owner, "ACCOUNTANT");
        String sales = staff(owner, "SALES");
        assertThat(get("/property/units", accountant).status()).isEqualTo(200);
        assertThat(get("/property/units", sales).status()).isEqualTo(403);
        assertThat(get("/pipeline", sales).status()).isEqualTo(200);
        assertThat(get("/pipeline", accountant).status()).isEqualTo(403);
        assertThat(post("/spaces", accountant, Map.of("name", "X1", "kind", "SHOP")).status()).isEqualTo(403);
    }
}
