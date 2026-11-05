package com.lezitech.suite.mpesa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * M-Pesa settings, all from environment variables. Modes:
 * off (default), sandbox/production (real Daraja), simulate (local only: no money moves, the
 * payment confirms itself a few seconds later so the till can be demoed).
 */
@Component
public class MpesaConfig {

    final String mode;
    final String baseUrl;
    final String consumerKey;
    final String consumerSecret;
    final String shortcode;
    final String passkey;
    final String callbackBase;
    final String subscriptionShortcode;
    final String c2bSecret;
    final long simulateDelayMs;

    public MpesaConfig(@Value("${suite.mpesa.mode:off}") String mode,
                       @Value("${suite.mpesa.base-url:https://sandbox.safaricom.co.ke}") String baseUrl,
                       @Value("${suite.mpesa.consumer-key:}") String consumerKey,
                       @Value("${suite.mpesa.consumer-secret:}") String consumerSecret,
                       @Value("${suite.mpesa.shortcode:174379}") String shortcode,
                       @Value("${suite.mpesa.passkey:}") String passkey,
                       @Value("${suite.mpesa.callback-base:}") String callbackBase,
                       @Value("${suite.mpesa.subscription-shortcode:}") String subscriptionShortcode,
                       @Value("${suite.mpesa.c2b-secret:}") String c2bSecret,
                       @Value("${suite.mpesa.simulate-delay-ms:4000}") long simulateDelayMs) {
        boolean realDb = System.getenv("DATABASE_URL") != null;
        // The simulator would mark sales paid without money: never on a real database.
        this.mode = "simulate".equals(mode) && realDb ? "off" : mode;
        this.baseUrl = baseUrl;
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.shortcode = shortcode;
        this.passkey = passkey;
        this.callbackBase = callbackBase;
        this.subscriptionShortcode = subscriptionShortcode.isBlank() ? shortcode : subscriptionShortcode;
        this.c2bSecret = c2bSecret;
        this.simulateDelayMs = simulateDelayMs;
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
}
