package com.lezitech.suite.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.SecretBox;
import com.lezitech.suite.mpesa.DarajaGateway;
import com.lezitech.suite.mpesa.MpesaConfig;
import com.lezitech.suite.mpesa.PayAccount;
import com.lezitech.suite.mpesa.PayHeroGateway;
import com.lezitech.suite.notify.AfricasTalking;
import com.lezitech.suite.notify.WhatsAppCloud;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Each business's own payment and SMS accounts, set by the owner (Settings, Payments) or by the LeziTech
 * team in the control panel. Keys are encrypted and never sent back in full: the screens show the last
 * four characters, and leaving a key blank when saving keeps the one already stored.
 *
 * Kinds:
 *  DARAJA  - Safaricom Daraja app: environment (sandbox|production), type (TILL|PAYBILL), shortcode
 *            (the paybill, or the till's store number), tillNumber (TILL only), accountReference (PAYBILL, optional);
 *            keys consumerKey, consumerSecret, passkey.
 *  PAYHERO - PayHero: channelId (the till or paybill linked in PayHero); keys username, password.
 *  SMS     - Africa's Talking: environment (sandbox|production), username, senderId (optional); key apiKey.
 *  WHATSAPP - Meta WhatsApp Cloud API: phoneNumberId, businessAccountId (optional), receiptTemplate, templateLanguage;
 *            key accessToken. Switched on, receipts and messages go through it; off, the app uses wa.me links.
 * Only one of DARAJA and PAYHERO is switched on at a time: the one the till uses for M-Pesa prompts.
 */
@Service
public class IntegrationService {

    public static final List<String> KINDS = List.of("DARAJA", "PAYHERO", "SMS", "WHATSAPP");

    private static final Map<String, List<String>> SETTINGS = Map.of(
            "DARAJA", List.of("environment", "type", "shortcode", "tillNumber", "accountReference"),
            "PAYHERO", List.of("channelId"),
            "SMS", List.of("environment", "username", "senderId"),
            "WHATSAPP", List.of("phoneNumberId", "businessAccountId", "receiptTemplate", "templateLanguage"));
    private static final Map<String, List<String>> SECRETS = Map.of(
            "DARAJA", List.of("consumerKey", "consumerSecret", "passkey"),
            "PAYHERO", List.of("username", "password"),
            "SMS", List.of("apiKey"),
            "WHATSAPP", List.of("accessToken"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretBox box;
    private final DarajaGateway daraja;
    private final PayHeroGateway payHero;
    private final AfricasTalking sms;
    private final MpesaConfig cfg;
    private final WhatsAppCloud whatsApp;

    public IntegrationService(JdbcTemplate jdbc, ObjectMapper json, SecretBox box, DarajaGateway daraja, PayHeroGateway payHero,
                              AfricasTalking sms, MpesaConfig cfg, WhatsAppCloud whatsApp) {
        this.jdbc = jdbc;
        this.json = json;
        this.box = box;
        this.daraja = daraja;
        this.payHero = payHero;
        this.sms = sms;
        this.cfg = cfg;
        this.whatsApp = whatsApp;
    }

    // ---------- reading ----------

    /** Every kind for a business, with keys masked. Kinds not set up yet come back empty. */
    public List<Map<String, Object>> list(UUID tenant) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String kind : KINDS) {
            out.add(view(tenant, kind));
        }
        return out;
    }

    public Map<String, Object> view(UUID tenant, String kind) {
        requireKind(kind);
        Optional<Map<String, Object>> row = row(tenant, kind);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("enabled", row.map(r -> r.get("enabled")).orElse(false));
        m.put("settings", row.map(r -> settingsOf(r)).orElse(Map.of()));
        Map<String, String> secrets = row.map(this::secretsOf).orElse(Map.of());
        Map<String, Object> masked = new LinkedHashMap<>();
        for (String k : SECRETS.get(kind)) {
            String v = secrets.get(k);
            masked.put(k, v == null || v.isBlank() ? null : "••••" + (v.length() > 4 ? v.substring(v.length() - 4) : ""));
        }
        m.put("secrets", masked);
        m.put("status", row.map(r -> r.get("status")).orElse("NOT_SET"));
        m.put("statusMessage", row.map(r -> r.get("status_message")).orElse(null));
        m.put("testedAt", row.map(r -> r.get("tested_at")).orElse(null));
        m.put("updatedBy", row.map(r -> r.get("updated_by")).orElse(null));
        m.put("updatedAt", row.map(r -> r.get("updated_at")).orElse(null));
        return m;
    }

    /** The account the till sends M-Pesa prompts through, if one is switched on and complete. */
    public Optional<PayAccount> tillAccount(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM integrations WHERE tenant_id = ? AND enabled AND kind IN ('DARAJA', 'PAYHERO')", tenant);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(account(rows.get(0)));
    }

    /** The business's PayHero account, switched on or not (to re-check a payment it already started). */
    public Optional<PayAccount.PayHero> payHeroAccount(UUID tenant) {
        return row(tenant, "PAYHERO").map(r -> (PayAccount.PayHero) account(r));
    }

    /** The business's own WhatsApp Business number, when it is switched on and complete (otherwise the app uses wa.me links). */
    public record WhatsAppAccount(String phoneNumberId, String accessToken, String receiptTemplate, String language) {
    }

    public Optional<WhatsAppAccount> whatsAppAccount(UUID tenant) {
        return row(tenant, "WHATSAPP").filter(r -> Boolean.TRUE.equals(r.get("enabled"))).flatMap(r -> {
            Map<String, Object> s = settingsOf(r);
            Map<String, String> k = secretsOf(r);
            if (!missing("WHATSAPP", s, k).isEmpty()) {
                return Optional.empty();
            }
            Object lang = s.get("templateLanguage");
            return Optional.of(new WhatsAppAccount((String) s.get("phoneNumberId"), k.get("accessToken"), (String) s.get("receiptTemplate"),
                    lang == null ? "en" : lang.toString()));
        });
    }

    /** Tenants whose Daraja till or paybill is this shortcode (for payments made straight to the number). */
    public List<UUID> tenantsForShortcode(String shortcode) {
        return jdbc.queryForList("""
                SELECT tenant_id FROM integrations WHERE kind = 'DARAJA'
                  AND (settings->>'shortcode' = ? OR settings->>'tillNumber' = ?)
                UNION SELECT id FROM tenants WHERE settings->>'mpesaTill' = ?""", UUID.class, shortcode, shortcode, shortcode);
    }

    // ---------- changing ----------

    /** Save settings and any keys given (blank keys keep the stored ones). Switching DARAJA or PAYHERO on switches the other off. */
    @Transactional
    public Map<String, Object> save(UUID tenant, String kind, Map<String, Object> raw, String who) {
        requireKind(kind);
        Body b = new Body(raw);
        Optional<Map<String, Object>> existing = row(tenant, kind);
        Map<String, Object> settings = new LinkedHashMap<>(existing.map(this::settingsOf).orElse(Map.of()));
        Map<String, Object> incoming = b.map("settings");
        if (incoming != null) {
            for (String k : SETTINGS.get(kind)) {
                if (incoming.containsKey(k)) {
                    Object v = incoming.get(k);
                    String s = v == null ? "" : v.toString().trim();
                    if (s.isEmpty()) {
                        settings.remove(k);
                    } else {
                        settings.put(k, s);
                    }
                }
            }
        }
        Map<String, String> secrets = new LinkedHashMap<>(existing.map(this::secretsOf).orElse(Map.of()));
        Map<String, Object> newSecrets = b.map("secrets");
        if (newSecrets != null) {
            for (String k : SECRETS.get(kind)) {
                Object v = newSecrets.get(k);
                if (v != null && !v.toString().isBlank()) {
                    secrets.put(k, v.toString().trim());
                }
            }
        }
        validate(kind, settings);
        boolean enabled = b.has("enabled") ? b.bool("enabled", false) : existing.map(r -> Boolean.TRUE.equals(r.get("enabled"))).orElse(false);
        if (enabled) {
            List<String> missing = missing(kind, settings, secrets);
            if (!missing.isEmpty()) {
                throw ApiException.bad("Fill in " + String.join(", ", missing) + " before switching this on");
            }
        }
        String secretJson = secrets.isEmpty() ? null : box.encrypt(write(secrets));
        jdbc.update("""
                INSERT INTO integrations (tenant_id, kind, enabled, settings, secrets, status, status_message, tested_at, updated_by, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, 'UNTESTED', NULL, NULL, ?, now())
                ON CONFLICT (tenant_id, kind) DO UPDATE SET enabled = EXCLUDED.enabled, settings = EXCLUDED.settings,
                  secrets = EXCLUDED.secrets, status = 'UNTESTED', status_message = NULL, tested_at = NULL,
                  updated_by = EXCLUDED.updated_by, updated_at = now()""",
                tenant, kind, enabled, write(settings), secretJson, who);
        if (enabled && ("DARAJA".equals(kind) || "PAYHERO".equals(kind))) {
            jdbc.update("UPDATE integrations SET enabled = false WHERE tenant_id = ? AND kind IN ('DARAJA', 'PAYHERO') AND kind <> ?", tenant, kind);
        }
        return view(tenant, kind);
    }

    @Transactional
    public void remove(UUID tenant, String kind) {
        requireKind(kind);
        jdbc.update("DELETE FROM integrations WHERE tenant_id = ? AND kind = ?", tenant, kind);
    }

    /** Try the keys against the provider and remember the result. Never throws for a provider's "no". */
    public Map<String, Object> test(UUID tenant, String kind) {
        requireKind(kind);
        Map<String, Object> r = row(tenant, kind).orElseThrow(() -> ApiException.bad("Save the details first, then test them"));
        Map<String, Object> settings = settingsOf(r);
        Map<String, String> secrets = secretsOf(r);
        List<String> missing = missing(kind, settings, secrets);
        String status;
        String message;
        if (!missing.isEmpty()) {
            status = "FAILED";
            message = "Missing: " + String.join(", ", missing) + ".";
        } else {
            try {
                message = switch (kind) {
                    case "DARAJA" -> testDaraja((PayAccount.Daraja) account(r));
                    case "PAYHERO" -> {
                        payHero.test((PayAccount.PayHero) account(r));
                        yield "PayHero accepted the API username and password. Make sure channel " + settings.get("channelId")
                                + " is the till or paybill this business is paid on; the first prompt confirms it.";
                    }
                    case "WHATSAPP" -> {
                        String number = whatsApp.test((String) settings.get("phoneNumberId"), secrets.get("accessToken"));
                        yield "WhatsApp accepted the number" + (number.isBlank() ? "" : " " + number)
                                + ". Receipts use the template \"" + settings.getOrDefault("receiptTemplate", "") + "\"; it must be approved in WhatsApp Manager.";
                    }
                    default -> {
                        String balance = sms.test(smsBase(settings), (String) settings.get("username"), secrets.get("apiKey"));
                        yield "Africa's Talking accepted the account" + (balance.isBlank() ? "." : ". Balance: " + balance + ".");
                    }
                };
                status = "OK";
            } catch (ApiException e) {
                status = "FAILED";
                message = e.getMessage();
            }
        }
        jdbc.update("UPDATE integrations SET status = ?, status_message = ?, tested_at = now() WHERE tenant_id = ? AND kind = ?",
                status, message, tenant, kind);
        return view(tenant, kind);
    }

    private String testDaraja(PayAccount.Daraja a) {
        daraja.test(a);
        String msg = "Safaricom accepted the consumer key and secret. The passkey is checked by the first real prompt.";
        boolean live = DarajaGateway.PRODUCTION.equals(a.baseUrl());
        if (live && !cfg.callbackBase().isBlank() && !cfg.c2bSecret().isBlank()) {
            String base = cfg.callbackBase().replaceAll("/+$", "") + "/api/public/mpesa/c2b/" + cfg.c2bSecret();
            try {
                daraja.registerC2bUrls(a, base + "/confirmation", base + "/validation");
                msg += " Payments made straight to " + a.partyB() + " will now show up in Lezi Suite.";
            } catch (ApiException e) {
                msg += " Registering direct payments to " + a.partyB() + " didn't work (" + e.getMessage() + ").";
            }
        }
        return msg;
    }

    // ---------- helpers ----------

    private PayAccount account(Map<String, Object> r) {
        Map<String, Object> s = settingsOf(r);
        Map<String, String> k = secretsOf(r);
        String kind = (String) r.get("kind");
        if (!missing(kind, s, k).isEmpty()) {
            return null;
        }
        if ("DARAJA".equals(kind)) {
            boolean till = "TILL".equals(s.get("type"));
            String base = "production".equals(s.get("environment")) ? DarajaGateway.PRODUCTION : DarajaGateway.SANDBOX;
            String shortcode = (String) s.get("shortcode");
            return new PayAccount.Daraja(base, k.get("consumerKey"), k.get("consumerSecret"), k.get("passkey"), till, shortcode,
                    till ? (String) s.get("tillNumber") : shortcode, till ? null : (String) s.get("accountReference"));
        }
        if ("PAYHERO".equals(kind)) {
            return new PayAccount.PayHero(PayHeroGateway.BASE_URL, k.get("username"), k.get("password"), Long.parseLong((String) s.get("channelId")));
        }
        return null;
    }

    private static String smsBase(Map<String, Object> s) {
        return "sandbox".equals(s.get("environment")) ? AfricasTalking.SANDBOX : AfricasTalking.LIVE;
    }

    private static List<String> missing(String kind, Map<String, Object> s, Map<String, String> k) {
        List<String> out = new ArrayList<>();
        switch (kind) {
            case "DARAJA" -> {
                need(out, s, "type", "till or paybill");
                need(out, s, "shortcode", "TILL".equals(s.get("type")) ? "store number" : "paybill number");
                if ("TILL".equals(s.get("type"))) {
                    need(out, s, "tillNumber", "till number");
                }
                needKey(out, k, "consumerKey", "consumer key");
                needKey(out, k, "consumerSecret", "consumer secret");
                needKey(out, k, "passkey", "passkey");
            }
            case "PAYHERO" -> {
                need(out, s, "channelId", "channel ID");
                needKey(out, k, "username", "API username");
                needKey(out, k, "password", "API password");
            }
            case "WHATSAPP" -> {
                need(out, s, "phoneNumberId", "phone number ID");
                need(out, s, "receiptTemplate", "receipt template name");
                needKey(out, k, "accessToken", "access token");
            }
            default -> {
                need(out, s, "username", "username");
                needKey(out, k, "apiKey", "API key");
            }
        }
        return out;
    }

    private static void need(List<String> out, Map<String, Object> s, String key, String label) {
        Object v = s.get(key);
        if (v == null || v.toString().isBlank()) {
            out.add(label);
        }
    }

    private static void needKey(List<String> out, Map<String, String> k, String key, String label) {
        String v = k.get(key);
        if (v == null || v.isBlank()) {
            out.add(label);
        }
    }

    private static void validate(String kind, Map<String, Object> s) {
        switch (kind) {
            case "DARAJA" -> {
                oneOf(s, "environment", List.of("sandbox", "production"), "Environment");
                oneOf(s, "type", List.of("TILL", "PAYBILL"), "Type");
                digits(s, "shortcode", "The paybill or store number");
                digits(s, "tillNumber", "The till number");
                Object ref = s.get("accountReference");
                if (ref != null && ref.toString().length() > 12) {
                    throw ApiException.bad("The account number customers pay to can be at most 12 characters");
                }
            }
            case "PAYHERO" -> {
                Object c = s.get("channelId");
                if (c != null && !c.toString().matches("[1-9][0-9]{0,9}")) {
                    throw ApiException.bad("The PayHero channel ID is a number, from PayHero, Payment Channels");
                }
            }
            case "WHATSAPP" -> {
                for (String key : List.of("phoneNumberId", "businessAccountId")) {
                    Object v = s.get(key);
                    if (v != null && !v.toString().matches("[0-9]{6,20}")) {
                        throw ApiException.bad("The " + ("phoneNumberId".equals(key) ? "phone number ID" : "WhatsApp Business account ID")
                                + " is a long number from WhatsApp Manager, not the phone number itself");
                    }
                }
                Object t = s.get("receiptTemplate");
                if (t != null && !t.toString().matches("[a-z0-9_]{1,512}")) {
                    throw ApiException.bad("A template name uses lower-case letters, numbers and underscores, e.g. lezi_receipt");
                }
                Object lang = s.get("templateLanguage");
                if (lang != null && !lang.toString().matches("[a-z]{2,3}(_[A-Z]{2})?")) {
                    throw ApiException.bad("The template language is a code such as en, en_US or sw");
                }
            }
            default -> {
                oneOf(s, "environment", List.of("sandbox", "production"), "Environment");
                Object sender = s.get("senderId");
                if (sender != null && sender.toString().length() > 11) {
                    throw ApiException.bad("A sender ID is at most 11 characters");
                }
            }
        }
    }

    private static void oneOf(Map<String, Object> s, String key, List<String> allowed, String label) {
        Object v = s.get(key);
        if (v != null && !allowed.contains(v.toString())) {
            throw ApiException.bad(label + " must be one of " + String.join(", ", allowed));
        }
    }

    private static void digits(Map<String, Object> s, String key, String label) {
        Object v = s.get(key);
        if (v != null && !v.toString().matches("[0-9]{5,10}")) {
            throw ApiException.bad(label + " should be 5 to 10 digits");
        }
    }

    private static void requireKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw ApiException.notFound("Integration");
        }
    }

    private Optional<Map<String, Object>> row(UUID tenant, String kind) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM integrations WHERE tenant_id = ? AND kind = ?", tenant, kind);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Map<String, Object> settingsOf(Map<String, Object> r) {
        try {
            Object v = r.get("settings");
            return json.readValue(v == null ? "{}" : v.toString(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, String> secretsOf(Map<String, Object> r) {
        String enc = (String) r.get("secrets");
        if (enc == null) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(box.decrypt(enc), new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
