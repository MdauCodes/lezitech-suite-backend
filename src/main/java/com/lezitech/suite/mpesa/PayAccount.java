package com.lezitech.suite.mpesa;

/** Where an M-Pesa prompt is sent from: a business's own Daraja app or PayHero account, or LeziTech's own for plan payments. */
public sealed interface PayAccount permits PayAccount.Daraja, PayAccount.PayHero {

    String provider();

    /**
     * A Safaricom Daraja app. For a paybill, {@code shortcode} and {@code partyB} are both the paybill number.
     * For a Buy Goods till, {@code shortcode} is the store (head office) number the passkey belongs to and
     * {@code partyB} is the till number customers pay.
     */
    record Daraja(String baseUrl, String consumerKey, String consumerSecret, String passkey,
                  boolean buyGoods, String shortcode, String partyB, String accountReference) implements PayAccount {
        public String provider() {
            return "DARAJA";
        }
    }

    /** A PayHero account (API username and password from PayHero, Account, API Keys) and the payment channel the money goes to. */
    record PayHero(String baseUrl, String username, String password, long channelId) implements PayAccount {
        public String provider() {
            return "PAYHERO";
        }
    }
}
