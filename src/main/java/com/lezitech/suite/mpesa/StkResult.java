package com.lezitech.suite.mpesa;

/** What a provider answered when it accepted an M-Pesa prompt. {@code providerRef} is PayHero's reference for status checks. */
public record StkResult(String checkoutRequestId, String merchantRequestId, String providerRef) {
}
