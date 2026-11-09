package com.lezitech.suite.mpesa;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Local-only stand-in for Safaricom and PayHero: no money moves. A few seconds after the "prompt", it
 * reports the result the provider would, so the whole till flow can be demoed and tested. A phone number
 * ending in 9999 simulates the customer cancelling.
 */
public class SimulatedGateway {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mpesa-simulator");
        t.setDaemon(true);
        return t;
    });
    private final long delayMs;
    private final Consumer<SimulatedResult> deliver;

    public record SimulatedResult(String callbackToken, String checkoutRequestId, boolean success, BigDecimal amount, String receipt) {
    }

    public SimulatedGateway(long delayMs, Consumer<SimulatedResult> deliver) {
        this.delayMs = delayMs;
        this.deliver = deliver;
    }

    public StkResult stkPush(String phone, BigDecimal amount, String callbackToken) {
        String checkout = "ws_CO_SIM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String merchant = "SIM-" + UUID.randomUUID().toString().substring(0, 8);
        boolean success = !phone.endsWith("9999");
        String receipt = "SIM" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
        timer.schedule(() -> deliver.accept(new SimulatedResult(callbackToken, checkout, success, amount, receipt)),
                delayMs, TimeUnit.MILLISECONDS);
        return new StkResult(checkout, merchant, null);
    }
}
