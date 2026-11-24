package com.lezitech.suite;

import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A control-panel person who types their details on the business sign-in page is pointed to the right page. */
class ControlPanelOnBusinessLoginTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    com.lezitech.suite.auth.LoginThrottle throttle;

    /** Wrong tries from this machine share one address limit for the whole test run; give back what these tests used. */
    @AfterEach
    @SuppressWarnings("unchecked")
    void forgetFailures() throws Exception {
        var f = com.lezitech.suite.auth.LoginThrottle.class.getDeclaredField("failures");
        f.setAccessible(true);
        ((java.util.Map<String, ?>) f.get(throttle)).clear();
    }

    String adminEmail(String extraColumns, String extraValues) {
        String email = "staff-" + UUID.randomUUID().toString().substring(0, 8) + "@lezitech.test";
        jdbc.update("INSERT INTO platform_admins (email, name, password_hash, role" + extraColumns + ") VALUES (?, 'Test Staff', ?, 'SUPPORT'" + extraValues + ")",
                email, encoder.encode("staff-pass-123"));
        return email;
    }

    @Test
    void rightPasswordGetsUseControlPanelAndNoToken() {
        String email = adminEmail("", "");
        Res r = post("/auth/login", null, Map.of("email", email, "password", "staff-pass-123"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.at("/code").asText()).isEqualTo("USE_CONTROL_PANEL");
        assertThat(r.at("/message").asText()).isEqualTo("This is a control-panel account. Sign in on the control-panel page.");
        assertThat(r.at("/token").isMissingNode()).isTrue();
    }

    @Test
    void wrongPasswordIsAnOrdinaryBadLogin() {
        String email = adminEmail("", "");
        Res r = post("/auth/login", null, Map.of("email", email, "password", "not-it"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.at("/code").asText()).isEqualTo("BAD_LOGIN");
    }

    @Test
    void blockedOrPendingStaffGetBadLoginEvenWithTheRightPassword() {
        String pending = adminEmail(", invite_pending", ", true");
        String blocked = adminEmail(", blocked_at", ", now()");
        String off = adminEmail(", active", ", false");
        for (String email : new String[]{pending, blocked, off}) {
            Res r = post("/auth/login", null, Map.of("email", email, "password", "staff-pass-123"));
            assertThat(r.status()).as(email).isEqualTo(401);
            assertThat(r.at("/code").asText()).isEqualTo("BAD_LOGIN");
        }
    }

    @Test
    void wrongPasswordsHereCountTowardsTheControlPanelLimit() {
        String email = adminEmail("", "");
        for (int i = 0; i < 5; i++) {
            assertThat(post("/auth/login", null, Map.of("email", email, "password", "wrong" + i)).status()).isEqualTo(401);
        }
        // Locked on the control-panel page too, even with the right password.
        assertThat(post("/admin/auth/login", null, Map.of("email", email, "password", "staff-pass-123")).status()).isEqualTo(429);
    }

    @Test
    void aNormalBusinessLoginIsUnchanged() {
        var s = signup("retail");
        Res r = post("/auth/login", null, Map.of("email", s.get("email").asText(), "password", "password123"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/token").asText()).isNotBlank();
        assertThat(post("/auth/login", null, Map.of("email", "nobody-" + UUID.randomUUID() + "@test.local", "password", "x")).at("/code").asText()).isEqualTo("BAD_LOGIN");
    }
}
