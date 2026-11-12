package com.lezitech.suite;

import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A cashier emails the receipt to the customer, in the business's name. */
class ReceiptEmailTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    @Test
    void receiptGoesToTheCustomerWithLinesTotalsAndPayments() {
        EmailSender.LogSender outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0)); // cashiers open their till before taking money
        String sugar = item(owner, "Sugar").get("id").asText();
        String id = post("/orders", cashier, Map.of("lines", line(sugar, 2),
                "payments", List.of(Map.of("method", "MPESA", "amount", 380, "reference", "TKL1ABC2DE")))).at("/id").asText();

        assertThat(post("/orders/" + id + "/email-receipt", cashier, Map.of("email", "not-an-email")).status()).isEqualTo(400);
        Res sent = post("/orders/" + id + "/email-receipt", cashier, Map.of("email", "Wanjiru@Example.test"));
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);

        // The email goes out in the background a moment later.
        EmailSender.Email e = null;
        for (int i = 0; i < 60 && e == null; i++) {
            e = outbox.sent.stream().filter(m -> m.to().equals("wanjiru@example.test")).findFirst().orElse(null);
            if (e == null) {
                try { Thread.sleep(150); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
            }
        }
        assertThat(e).as("receipt email sent").isNotNull();
        assertThat(e.to()).isEqualTo("wanjiru@example.test");
        assertThat(e.subject()).startsWith("Receipt #").contains("from Test retail");
        assertThat(e.text()).contains("2 x Sugar").contains("Total: KES 380.00").contains("M-Pesa: KES 380.00 (TKL1ABC2DE)");
        // Not VAT-registered (nothing chosen on the Tax page): no VAT on the customer's receipt.
        assertThat(e.html()).doesNotContain("VAT");
        assertThat(e.text()).doesNotContain("VAT");

        // Once the owner says they're VAT-registered, the receipt shows the VAT inside the total.
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("tax", Map.of("regime", "VAT")))).status()).isEqualTo(200);
        post("/orders/" + id + "/email-receipt", cashier, Map.of("email", "vat@example.test"));
        EmailSender.Email vat = null;
        for (int i = 0; i < 60 && vat == null; i++) {
            vat = outbox.sent.stream().filter(m -> m.to().equals("vat@example.test")).findFirst().orElse(null);
            if (vat == null) {
                try { Thread.sleep(150); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
            }
        }
        assertThat(vat).as("second receipt sent").isNotNull();
        assertThat(vat.html()).contains("VAT included");
    }
}
