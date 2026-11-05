package com.lezitech.suite.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupSafetyCheckTest {

    private static final String GOOD_SECRET = "x".repeat(64);
    private static final String GOOD_KEY = "k".repeat(32);

    @Test
    void localEmbeddedRunsMayUseDevSecrets() {
        assertThatCode(() -> StartupSafetyCheck.verify(true, StartupSafetyCheck.DEV_JWT_SECRET, StartupSafetyCheck.DEV_OPERATOR_KEY))
                .doesNotThrowAnyException();
    }

    @Test
    void realDatabaseRefusesDefaultsShortOrMissingSecrets() {
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, StartupSafetyCheck.DEV_JWT_SECRET, GOOD_KEY)).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, "short", GOOD_KEY)).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, GOOD_SECRET, StartupSafetyCheck.DEV_OPERATOR_KEY)).hasMessageContaining("OPERATOR_KEY");
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, GOOD_SECRET, "")).hasMessageContaining("OPERATOR_KEY");
    }

    @Test
    void realDatabaseWithProperSecretsStarts() {
        assertThatCode(() -> StartupSafetyCheck.verify(false, GOOD_SECRET, GOOD_KEY)).doesNotThrowAnyException();
    }
}
