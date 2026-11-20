package com.lezitech.suite.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogFilterTest {

    private static final String TOKEN = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Test
    void stkAndPayHeroCallbackUrisAreLoggedWithTheTokenMasked() {
        assertThat(RequestLogFilter.maskSecrets("/api/public/mpesa/stk/" + TOKEN)).isEqualTo("/api/public/mpesa/stk/***");
        assertThat(RequestLogFilter.maskSecrets("/api/public/payhero/" + TOKEN)).isEqualTo("/api/public/payhero/***");
    }

    @Test
    void c2bCallbackUrisAreLoggedWithTheSharedSecretMaskedAndTheRestKept() {
        assertThat(RequestLogFilter.maskSecrets("/api/public/mpesa/c2b/" + TOKEN + "/validation")).isEqualTo("/api/public/mpesa/c2b/***/validation");
        assertThat(RequestLogFilter.maskSecrets("/api/public/mpesa/c2b/" + TOKEN + "/confirmation")).isEqualTo("/api/public/mpesa/c2b/***/confirmation");
    }

    @Test
    void normalUrisAreLoggedUnchanged() {
        assertThat(RequestLogFilter.maskSecrets("/api/orders/123")).isEqualTo("/api/orders/123");
        assertThat(RequestLogFilter.maskSecrets("/api/mpesa/till-payments/5/ignore")).isEqualTo("/api/mpesa/till-payments/5/ignore");
    }
}
