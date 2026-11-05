package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Guessing is slowed down, and old tokens stop working when someone is switched off. */
class LoginHardeningTest extends IntegrationTest {

    @Test
    void tooManyWrongPasswordsLocksTheAccount() {
        JsonNode s = signup("retail");
        String email = s.get("email").asText();
        for (int i = 0; i < 5; i++) {
            assertThat(post("/auth/login", null, Map.of("email", email, "password", "wrong" + i)).status()).isEqualTo(401);
        }
        Res locked = post("/auth/login", null, Map.of("email", email, "password", "password123"));
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.at("/code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    void tooManyWrongPinsLocksThatPin() {
        String owner = token(signup("retail"));
        staff(owner, "CASHIER");
        String cashierId = userId(owner, "CASHIER");
        for (int i = 0; i < 5; i++) {
            assertThat(post("/auth/pin", owner, Map.of("userId", cashierId, "pin", "000" + i)).status()).isEqualTo(401);
        }
        assertThat(post("/auth/pin", owner, Map.of("userId", cashierId, "pin", "1234")).status()).isEqualTo(429);
    }

    @Test
    void switchingSomeoneOffOrChangingTheirPasswordEndsTheirSessions() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        String cashierId = userId(owner, "CASHIER");
        assertThat(get("/auth/me", cashier).status()).isEqualTo(200);

        // Password change: the old token is dead straight away.
        call("PUT", "/team/" + cashierId, owner, Map.of("name", "CASHIER person", "role", "CASHIER", "active", true, "password", "newpassword1"));
        assertThat(get("/auth/me", cashier).status()).isEqualTo(401);

        // A fresh login works, then switching them off kills it too.
        String email = get("/team", owner).body().findValuesAsText("email").stream().filter(e -> e.startsWith("cashier")).findFirst().orElseThrow();
        String fresh = post("/auth/login", null, Map.of("email", email, "password", "newpassword1")).body().get("token").asText();
        assertThat(get("/auth/me", fresh).status()).isEqualTo(200);
        call("PUT", "/team/" + cashierId, owner, Map.of("name", "CASHIER person", "role", "CASHIER", "active", false));
        assertThat(get("/auth/me", fresh).status()).isEqualTo(401);
    }

    @Test
    void tokensCanBeRenewedSilently() {
        String owner = token(signup("retail"));
        Res r = post("/auth/refresh", owner, Map.of());
        assertThat(r.status()).isEqualTo(200);
        assertThat(get("/auth/me", r.body().get("token").asText()).status()).isEqualTo(200);
        assertThat(post("/auth/refresh", null, Map.of()).status()).isEqualTo(401);
    }
}
