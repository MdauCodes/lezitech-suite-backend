package com.lezitech.suite.growth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Bring a business, you both get a month free." Every workspace has a short code (shown in Billing); a business that
 * signs up through lezitech.site/suite?ref=CODE is linked to the one that referred it. When the new business pays for
 * the first time, both get one extra month.
 */
@Component
public class Referrals {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public Referrals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The workspace's code, made on first use. */
    public String codeFor(UUID tenant) {
        String code = jdbc.queryForObject("SELECT referral_code FROM tenants WHERE id = ?", String.class, tenant);
        if (code != null) {
            return code;
        }
        for (int i = 0; i < 10; i++) {
            String c = newCode();
            if (jdbc.update("UPDATE tenants SET referral_code = ? WHERE id = ? AND referral_code IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM tenants WHERE referral_code = ?)", c, tenant, c) == 1) {
                return c;
            }
            String now = jdbc.queryForObject("SELECT referral_code FROM tenants WHERE id = ?", String.class, tenant);
            if (now != null) {
                return now;
            }
        }
        throw new IllegalStateException("Couldn't make a referral code");
    }

    /** Link a new workspace to the one whose code it signed up with (not to itself, not twice). */
    public void link(UUID newTenant, String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        List<UUID> by = jdbc.queryForList("SELECT id FROM tenants WHERE referral_code = ? AND NOT is_demo", UUID.class,
                code.trim().toUpperCase());
        if (!by.isEmpty() && !by.get(0).equals(newTenant)) {
            jdbc.update("UPDATE tenants SET referred_by = ? WHERE id = ? AND referred_by IS NULL", by.get(0), newTenant);
        }
    }

    /**
     * Called when a workspace pays: on its first payment, it and the business that referred it each get a month.
     * The referrer's month goes on top of what it has paid for (or its trial, if it hasn't paid yet).
     * @return the referrer's id when a reward was given
     */
    public UUID rewardOnFirstPayment(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT referred_by FROM tenants WHERE id = ? AND referred_by IS NOT NULL AND referral_rewarded_at IS NULL FOR UPDATE", tenant);
        if (rows.isEmpty()) {
            return null;
        }
        UUID referrer = (UUID) rows.get(0).get("referred_by");
        jdbc.update("UPDATE tenants SET referral_rewarded_at = now(), paid_until = paid_until + interval '1 month' WHERE id = ?", tenant);
        jdbc.update("""
                UPDATE tenants SET
                  paid_until = CASE WHEN status = 'ACTIVE' THEN greatest(coalesce(paid_until, now()), now()) + interval '1 month' ELSE paid_until END,
                  trial_ends_at = CASE WHEN status <> 'ACTIVE' THEN greatest(trial_ends_at, now()) + interval '1 month' ELSE trial_ends_at END
                WHERE id = ?""", referrer);
        return referrer;
    }

    private static String newCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
