package com.lezitech.suite.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupSafetyCheckTest {

    private static final String GOOD_SECRET = "x".repeat(64);
    private static final String GOOD_KEY = "k".repeat(32);
    private static final String SECRETS = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void localEmbeddedRunsMayUseDevSecrets() {
        assertThatCode(() -> StartupSafetyCheck.verify(true, StartupSafetyCheck.DEV_JWT_SECRET, StartupSafetyCheck.DEV_OPERATOR_KEY, SECRETS))
                .doesNotThrowAnyException();
    }

    @Test
    void realDatabaseRefusesDefaultsShortOrMissingSecrets() {
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, StartupSafetyCheck.DEV_JWT_SECRET, GOOD_KEY, SECRETS)).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, "short", GOOD_KEY, SECRETS)).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, GOOD_SECRET, StartupSafetyCheck.DEV_OPERATOR_KEY, SECRETS)).hasMessageContaining("OPERATOR_KEY");
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, GOOD_SECRET, "", SECRETS)).hasMessageContaining("OPERATOR_KEY");
    }

    @Test
    void realDatabaseRefusesAMissingSecretsKey() {
        assertThatThrownBy(() -> StartupSafetyCheck.verify(false, GOOD_SECRET, GOOD_KEY, "")).hasMessageContaining("SECRETS_KEY");
    }

    @Test
    void realDatabaseWithProperSecretsStarts() {
        assertThatCode(() -> StartupSafetyCheck.verify(false, GOOD_SECRET, GOOD_KEY, SECRETS)).doesNotThrowAnyException();
    }
}
