package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.etims.EtimsClient;
import com.lezitech.suite.etims.EtimsService;
import com.lezitech.suite.etims.StubEtimsClient;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Completed sales get a KRA invoice number (test numbers here), refunds a credit note, and outages are retried. */
class EtimsTest extends IntegrationTest {

    @Autowired
    EtimsService etims;

    private String shopWithPin() {
        String owner = token(signup("retail"));
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("kraPin", "P051234567X"))).status()).isEqualTo(200);
        return owner;
    }

    private JsonNode cashSale(String token) {
        String sugar = item(token, "Sugar").get("id").asText();
        Res r = post("/orders", token, Map.of("lines", line(sugar, 1), "payments", List.of(Map.of("method", "CASH", "amount", 1000))));
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    @Test
    void saleGetsAnInvoiceNumberAndRefundACreditNote() {
        String owner = shopWithPin();
        JsonNode sale = cashSale(owner);
        assertThat(sale.get("etimsStatus").asText()).isEqualTo("SIGNED");
        assertThat(sale.get("etimsInvoiceNo").asText()).startsWith("TEST-P051234567X-");
        assertThat(sale.get("etimsQr").asText()).contains("TEST ONLY");
        assertThat(sale.get("etimsTest").asBoolean()).isTrue();

        Res refund = post("/orders/" + sale.get("id").asText() + "/refund", owner, Map.of("reason", "Wrong size"));
        assertThat(refund.status()).isEqualTo(200);
        assertThat(get("/orders/" + sale.get("id").asText(), owner).at("/etimsCreditNo").asText()).startsWith("TEST-CN-");
    }

    @Test
    void shopsWithoutAKraPinAreNotSent() {
        String owner = token(signup("retail"));
        assertThat(cashSale(owner).get("etimsStatus").isNull()).isTrue();
    }

    @Test
    void whenKraIsDownTheSaleStillGoesThroughAndIsSentLater() {
        String owner = shopWithPin();
        etims.useClient(new EtimsClient() {
            public Signed sign(Invoice invoice) {
                throw new IllegalStateException("KRA not answering");
            }

            public String name() {
                return "down";
            }
        });
        String id;
        try {
            JsonNode sale = cashSale(owner);
            assertThat(sale.get("status").asText()).isEqualTo("PAID");
            assertThat(sale.get("etimsStatus").asText()).isEqualTo("PENDING");
            id = sale.get("id").asText();
        } finally {
            etims.useClient(new StubEtimsClient());
        }
        // Retries wait a little after each failure; make this one due now.
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("UPDATE etims_queue SET next_try_at = now() WHERE order_id = ?::uuid", id);
        etims.retryPending();
        assertThat(get("/orders/" + id, owner).at("/etimsStatus").asText()).isEqualTo("SIGNED");
    }

    @Autowired
    javax.sql.DataSource dataSource;
}
