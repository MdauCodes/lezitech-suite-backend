package com.lezitech.suite.attendance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The shop code: 8 digits from HMAC-SHA256 of the branch and the 30-second window, keyed with the branch's own secret.
 * The current window and the one before are accepted (a slow scan still works). A code from another branch never
 * matches because the secret and the branch are both part of it.
 */
public final class ShopCode {

    public static final int WINDOW_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShopCode() {
    }

    public static long window(Instant at) {
        return at.getEpochSecond() / WINDOW_SECONDS;
    }

    public static int secondsLeft(Instant at) {
        return WINDOW_SECONDS - (int) (at.getEpochSecond() % WINDOW_SECONDS);
    }

    public static String code(String secret, UUID branch, long window) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((branch + ":" + window).getBytes(StandardCharsets.UTF_8));
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%08d", bin % 100_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String current(String secret, UUID branch, Instant at) {
        return code(secret, branch, window(at));
    }

    public static boolean valid(String secret, UUID branch, String given, Instant at) {
        if (secret == null || given == null) {
            return false;
        }
        long w = window(at);
        boolean now = same(code(secret, branch, w), given);
        boolean before = same(code(secret, branch, w - 1), given);
        return now || before;
    }

    public static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The text inside the QR code on the shop screen. */
    public static String qr(String code) {
        return "lza:" + code;
    }

    /** The text inside the printed QR code. */
    public static String printedQr(String token) {
        return "lzp:" + token;
    }

    /** What the staff phone sent, without the QR prefix. */
    public static String bare(String scanned) {
        String s = scanned == null ? "" : scanned.trim();
        return s.startsWith("lza:") || s.startsWith("lzp:") ? s.substring(4) : s;
    }

    public static boolean isPrinted(String scanned) {
        return scanned != null && scanned.trim().startsWith("lzp:");
    }
}
