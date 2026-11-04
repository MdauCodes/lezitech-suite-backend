package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cashiers can't change prices or give discounts without a manager; nothing can go negative. */
class PriceControlTest extends IntegrationTest {

    @Test
    void cashierNeedsManagerForPriceChangesAndDiscounts() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String sugar = item(owner, "Sugar").get("id").asText();

        // Price change without approval: refused.
        Res r = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "unitPrice", 10))));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.at("/code").asText()).isEqualTo("APPROVAL_NEEDED");

        // Wrong PIN: refused. Right manager PIN: allowed.
        r = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "unitPrice", 150)),
                "approval", Map.of("userId", managerId, "pin", "9999")));
        assertThat(r.status()).isEqualTo(403);
        r = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "unitPrice", 150)),
                "approval", Map.of("userId", managerId, "pin", "1234")));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/total").decimalValue()).isEqualByComparingTo("150");

        // The same price as the shelf price is not an override.
        r = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "unitPrice", 190))));
        assertThat(r.status()).isEqualTo(200);

        // Bill discount at payment needs approval too.
        String orderId = r.at("/id").asText();
        r = post("/orders/" + orderId + "/pay", cashier, Map.of("discount", 10, "payments", List.of(Map.of("method", "CASH", "amount", 180))));
        assertThat(r.status()).isEqualTo(403);

        // A custom-priced line from the till needs approval.
        r = post("/orders", cashier, Map.of("lines", List.of(Map.of("name", "Anything", "unitPrice", 1, "qty", 1))));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void discountLimitAndNoNegativeTotals() {
        JsonNode s = signup("retail");
        String owner = token(s);
        staff(owner, "MANAGER");
        String manager = post("/auth/login", null, Map.of("email", get("/team", owner).body().findValuesAsText("email").stream()
                .filter(e -> e.startsWith("manager")).findFirst().orElseThrow(), "password", "password123")).body().get("token").asText();
        String sugar = item(owner, "Sugar").get("id").asText();

        // 50% off is above the default 20% limit, even for a manager.
        Res r = post("/orders", manager, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "discount", 95))));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.at("/message").asText()).contains("20%");

        // Within the limit is fine.
        r = post("/orders", manager, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "discount", 19))));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/total").decimalValue()).isEqualByComparingTo("171");

        // A discount bigger than the price, or a negative price, is rejected outright.
        assertThat(post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "discount", 500)))).status()).isEqualTo(400);
        assertThat(post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "unitPrice", -5)))).status()).isEqualTo(400);
        assertThat(post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "discount", -5)))).status()).isEqualTo(400);

        // The owner can raise the limit in settings.
        call("PUT", "/settings", owner, Map.of("settings", Map.of("maxDiscountPercent", 60)));
        r = post("/orders", manager, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 1, "discount", 95))));
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void addOnPricesComeFromTheMenuNotTheTill() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String waiter = staff(owner, "WAITER");
        JsonNode burger = item(owner, "Beef burger");
        Res r = post("/orders", waiter, Map.of("lines", List.of(Map.of("itemId", burger.get("id").asText(), "qty", 1,
                "options", List.of(Map.of("group", "Add-ons", "name", "Cheese", "price", -700))))));
        assertThat(r.status()).isEqualTo(200);
        // 750 burger + 80 cheese from the menu, not the -700 the till sent.
        assertThat(r.at("/total").decimalValue()).isEqualByComparingTo("830");
        r = post("/orders", waiter, Map.of("lines", List.of(Map.of("itemId", burger.get("id").asText(), "qty", 1,
                "options", List.of(Map.of("group", "Add-ons", "name", "Free lobster", "price", 0))))));
        assertThat(r.status()).isEqualTo(400);
    }
}
