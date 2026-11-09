package com.lezitech.suite.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts each business's payment and SMS keys before they are stored (AES-256-GCM).
 * The key comes from SECRETS_KEY (32 random bytes, base64: `openssl rand -base64 32`). Local runs on the
 * embedded database use a fixed development key; on a real database a missing key refuses to save or read.
 * Changing SECRETS_KEY makes saved keys unreadable, so every business would have to enter them again.
 */
@Component
public class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "v1:";

    private final SecretKeySpec key;

    public SecretBox(@Value("${suite.secrets-key:}") String configured) {
        this.key = keyFrom(configured, System.getenv("DATABASE_URL") != null);
    }

    static SecretKeySpec keyFrom(String configured, boolean realDb) {
        try {
            if (configured != null && !configured.isBlank()) {
                byte[] raw = Base64.getDecoder().decode(configured.trim());
                if (raw.length != 32) {
                    throw new IllegalStateException("SECRETS_KEY must be 32 bytes, base64 encoded (openssl rand -base64 32)");
                }
                return new SecretKeySpec(raw, "AES");
            }
            if (realDb) {
                return null;
            }
            byte[] dev = MessageDigest.getInstance("SHA-256").digest("lezi-suite-local-development-only".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(dev, "AES");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SECRETS_KEY isn't valid base64", e);
        }
    }

    public boolean ready() {
        return key != null;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, require(), new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't encrypt", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not an encrypted value");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, require(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SECRETS_UNREADABLE",
                    "Saved keys can't be read (SECRETS_KEY changed?). Enter them again under Payments.");
        }
    }

    private SecretKeySpec require() {
        if (key == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SECRETS_KEY_MISSING",
                    "The server has no SECRETS_KEY set, so payment keys can't be stored safely yet.");
        }
        return key;
    }
}
