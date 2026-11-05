package com.lezitech.suite.mpesa;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Local-only stand-in for Safaricom: no money moves. A few seconds after the "prompt", it
 * delivers the same success callback Daraja would, so the whole till flow can be demoed and
 * tested. A phone number ending in 9999 simulates the customer cancelling.
 */
public class SimulatedGateway implements MpesaGateway {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mpesa-simulator");
        t.setDaemon(true);
        return t;
    });
    private final long delayMs;
    private final Consumer<SimulatedCallback> deliver;

    public record SimulatedCallback(String callbackUrl, String checkoutRequestId, String merchantRequestId, boolean success,
                                    BigDecimal amount, String phone, String receipt) {
    }

    public SimulatedGateway(long delayMs, Consumer<SimulatedCallback> deliver) {
        this.delayMs = delayMs;
        this.deliver = deliver;
    }

    @Override
    public StkResult stkPush(String shortcode, String phone, BigDecimal amount, String accountRef, String description, String callbackUrl) {
        String checkout = "ws_CO_SIM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String merchant = "SIM-" + UUID.randomUUID().toString().substring(0, 8);
        boolean success = !phone.endsWith("9999");
        String receipt = "SIM" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
        timer.schedule(() -> deliver.accept(new SimulatedCallback(callbackUrl, checkout, merchant, success, amount, phone, receipt)),
                delayMs, TimeUnit.MILLISECONDS);
        return new StkResult(checkout, merchant);
    }

    @Override
    public void registerC2bUrls(String shortcode, String confirmationUrl, String validationUrl) {
        // nothing to register in simulation
    }
}
