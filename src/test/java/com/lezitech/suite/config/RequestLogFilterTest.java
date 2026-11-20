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

    @Test
    void theOneOffLinksWeSendCustomersAreMaskedToo() {
        // These reach a customer by email or WhatsApp and are the whole key to that stay, booking or statement.
        assertThat(RequestLogFilter.maskSecrets("/s/" + TOKEN)).isEqualTo("/s/***");
        assertThat(RequestLogFilter.maskSecrets("/s/" + TOKEN + "/pay")).isEqualTo("/s/***/pay");
        assertThat(RequestLogFilter.maskSecrets("/api/public/appointments/mama-nails/a/" + TOKEN))
                .isEqualTo("/api/public/appointments/mama-nails/a/***");
        assertThat(RequestLogFilter.maskSecrets("/api/public/statement/" + TOKEN)).isEqualTo("/api/public/statement/***");
        // A business's own short name is not a secret and stays readable.
        assertThat(RequestLogFilter.maskSecrets("/api/public/menu/mama-nails")).isEqualTo("/api/public/menu/mama-nails");
    }

    @Test
    void recordIdsOnSignedInPathsStayReadableForSupport() {
        // Same shape, but the caller is signed in and this is an ordinary record id, not a one-off link.
        assertThat(RequestLogFilter.maskSecrets("/api/orders/" + TOKEN + "/whatsapp-receipt"))
                .isEqualTo("/api/orders/" + TOKEN + "/whatsapp-receipt");
        assertThat(RequestLogFilter.maskSecrets("/api/items/3f8a1c2e-9b4d-4e1f-8a7b-0c5d6e7f8a9b"))
                .isEqualTo("/api/items/3f8a1c2e-9b4d-4e1f-8a7b-0c5d6e7f8a9b");
    }
}
