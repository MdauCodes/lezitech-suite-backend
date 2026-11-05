package com.lezitech.suite.etims;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Talks to KRA eTIMS (OSCU or VSCU). The rest of the system only sees this interface, so the
 * real integration can be dropped in once a KRA PIN is approved for eTIMS and a device is
 * registered, without touching the till.
 */
public interface EtimsClient {

    /** KRA tax bands: A exempt, B 16% VAT, C zero-rated, D non-VAT, E 8%. */
    record Line(String name, BigDecimal qty, BigDecimal unitPrice, String taxBand, BigDecimal total, BigDecimal tax) {
    }

    record Invoice(String kraPin, String branchId, String type, int number, String originalInvoiceNo, Instant date,
                   String customerPin, String customerName, String paymentMethod,
                   List<Line> lines, BigDecimal total, BigDecimal tax) {
    }

    /**
     * @param invoiceNo     KRA's invoice (receipt) number
     * @param signature     receipt signature from the control unit
     * @param controlUnitId SCU/SDC id
     * @param qr            what the receipt's QR code encodes (KRA verification link)
     * @param test          true when this did not go to KRA (not valid for tax)
     */
    record Signed(String invoiceNo, String signature, String controlUnitId, String qr, boolean test) {
    }

    Signed sign(Invoice invoice);

    String name();

    static String taxBand(BigDecimal rate) {
        int r = rate == null ? 0 : rate.intValue();
        return r == 16 ? "B" : r == 8 ? "E" : "C";
    }
}
