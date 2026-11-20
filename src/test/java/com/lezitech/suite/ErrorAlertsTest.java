package com.lezitech.suite;

import com.lezitech.suite.common.ErrorAlerts;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Server errors are grouped: a burst of the same fault is one line in one email, not one email each. */
class ErrorAlertsTest extends IntegrationTest {

    @Autowired
    ErrorAlerts alerts;

    private static RuntimeException fault(String message) {
        return new IllegalStateException(message);
    }

    @Test
    void sameFaultsAreGroupedAndSentOnce() {
        alerts.flush();
        for (int i = 0; i < 40; i++) {
            alerts.record(fault("boom " + i), "GET /api/orders");
        }
        alerts.record(new NullPointerException("no item"), "POST /api/orders");
        assertThat(alerts.waiting()).hasSize(2);

        String sent = alerts.flush();
        assertThat(sent).contains("41 server errors in 2 groups").contains("40 × IllegalStateException").contains("GET /api/orders")
                .contains("1 × NullPointerException");
        // Nothing new: nothing sent.
        assertThat(alerts.flush()).isNull();
    }
}
