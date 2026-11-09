package com.lezitech.suite.mpesa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Platform-wide M-Pesa settings, from environment variables.
 *
 * MPESA_MODE: off (default) | on | simulate. "sandbox" and "production" from older setups mean on.
 * When on, each business's till prompts go through its own Daraja app or PayHero account (Settings, Payments).
 * Simulate is local only: no money moves and payments confirm themselves a few seconds later.
 *
 * LeziTech's own plan payments go through LeziTech's PayHero account (PAYHERO_*) or, if that isn't set,
 * LeziTech's own Daraja paybill (MPESA_CONSUMER_KEY etc.).
 */
@Component
public class MpesaConfig {

    final String mode;
    final String callbackBase;
    final String c2bSecret;
    final long simulateDelayMs;
    final PayAccount.PayHero platformPayHero;
    final PayAccount.Daraja platformDaraja;

    public MpesaConfig(@Value("${suite.mpesa.mode:off}") String mode,
                       @Value("${suite.mpesa.base-url:https://sandbox.safaricom.co.ke}") String baseUrl,
                       @Value("${suite.mpesa.consumer-key:}") String consumerKey,
                       @Value("${suite.mpesa.consumer-secret:}") String consumerSecret,
                       @Value("${suite.mpesa.shortcode:174379}") String shortcode,
                       @Value("${suite.mpesa.passkey:}") String passkey,
                       @Value("${suite.mpesa.callback-base:}") String callbackBase,
                       @Value("${suite.mpesa.subscription-shortcode:}") String subscriptionShortcode,
                       @Value("${suite.mpesa.c2b-secret:}") String c2bSecret,
                       @Value("${suite.mpesa.simulate-delay-ms:4000}") long simulateDelayMs,
                       @Value("${suite.payhero.base-url:" + PayHeroGateway.BASE_URL + "}") String payHeroBase,
                       @Value("${suite.payhero.username:}") String payHeroUser,
                       @Value("${suite.payhero.password:}") String payHeroPassword,
                       @Value("${suite.payhero.channel-id:0}") long payHeroChannel) {
        boolean realDb = System.getenv("DATABASE_URL") != null;
        String m = mode == null ? "off" : mode.trim().toLowerCase();
        if (m.equals("sandbox") || m.equals("production")) {
            m = "on";
        }
        // The simulator would mark sales paid without money: never on a real database.
        this.mode = "simulate".equals(m) && realDb ? "off" : m;
        this.callbackBase = callbackBase;
        this.c2bSecret = c2bSecret;
        this.simulateDelayMs = simulateDelayMs;
        this.platformPayHero = payHeroUser.isBlank() || payHeroPassword.isBlank() || payHeroChannel <= 0 ? null
                : new PayAccount.PayHero(payHeroBase, payHeroUser, payHeroPassword, payHeroChannel);
        String sub = subscriptionShortcode.isBlank() ? shortcode : subscriptionShortcode;
        this.platformDaraja = consumerKey.isBlank() || consumerSecret.isBlank() || passkey.isBlank() ? null
                : new PayAccount.Daraja(baseUrl, consumerKey, consumerSecret, passkey, false, sub, sub, null);
    }

    public boolean enabled() {
        return !"off".equals(mode);
    }

    public boolean simulated() {
        return "simulate".equals(mode);
    }

    public String mode() {
        return mode;
    }

    public String callbackBase() {
        return callbackBase;
    }

    public String c2bSecret() {
        return c2bSecret;
    }

    /** LeziTech's own account for plan payments: PayHero first, then Daraja. Null when neither is set. */
    public PayAccount subscriptionAccount() {
        return platformPayHero != null ? platformPayHero : platformDaraja;
    }
}
