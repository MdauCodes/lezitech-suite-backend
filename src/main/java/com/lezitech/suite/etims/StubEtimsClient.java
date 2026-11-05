package com.lezitech.suite.etims;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Stand-in for KRA while there are no eTIMS sandbox credentials. It gives back numbers in the
 * same shape, clearly marked TEST, so receipts, refunds and the retry queue can be built and
 * checked end to end. Nothing it produces is valid for tax.
 */
public class StubEtimsClient implements EtimsClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public Signed sign(Invoice inv) {
        byte[] b = new byte[10];
        RANDOM.nextBytes(b);
        String sig = HexFormat.of().formatHex(b).toUpperCase();
        String prefix = "CREDIT_NOTE".equals(inv.type()) ? "TEST-CN-" : "TEST-";
        String no = prefix + inv.kraPin() + "-" + inv.number();
        String cu = "TESTCU" + inv.kraPin().substring(Math.max(0, inv.kraPin().length() - 4));
        String qr = "TEST ONLY - not a KRA receipt. Invoice " + no + " | signature " + sig + " | total " + inv.total().toPlainString();
        return new Signed(no, sig, cu, qr, true);
    }

    @Override
    public String name() {
        return "stub";
    }
}
