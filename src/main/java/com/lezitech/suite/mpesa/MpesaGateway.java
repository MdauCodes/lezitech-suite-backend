package com.lezitech.suite.mpesa;

import java.math.BigDecimal;

/** Sends an STK push ("enter your M-Pesa PIN" prompt) to a customer's phone. */
public interface MpesaGateway {

    record StkResult(String checkoutRequestId, String merchantRequestId) {
    }

    /**
     * @param shortcode  the till/paybill being paid
     * @param phone      2547XXXXXXXX
     * @param callbackUrl where Safaricom posts the result
     */
    StkResult stkPush(String shortcode, String phone, BigDecimal amount, String accountRef, String description, String callbackUrl);

    /** Tell Safaricom where to send direct till/paybill payments (C2B). */
    void registerC2bUrls(String shortcode, String confirmationUrl, String validationUrl);
}
