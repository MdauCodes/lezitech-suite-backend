package com.lezitech.suite.notify;

import com.lezitech.suite.common.Db;
import com.lezitech.suite.common.SecretBox;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone and computer notifications (Web Push, as Moments Packaging does it) so staff never miss an order, even with the
 * app closed or the phone locked. Each staff member turns them on per device. The key pair is made once and kept by
 * BizNez (the private half encrypted); VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY override it. Sending happens after the
 * change is saved, in the background; a device that has gone away (404/410) is forgotten.
 */
@Service
public class WebPush {

    private static final Logger log = LoggerFactory.getLogger(WebPush.class);

    private final Db db;
    private final JdbcTemplate jdbc;
    private final SecretBox box;
    private final String configuredPublic;
    private final String configuredPrivate;
    private final String subject;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-push");
        t.setDaemon(true);
        return t;
    });

    private volatile PushService push;
    private volatile String publicKey;

    public WebPush(Db db, JdbcTemplate jdbc, SecretBox box,
                   @Value("${suite.push.public-key:}") String configuredPublic,
                   @Value("${suite.push.private-key:}") String configuredPrivate,
                   @Value("${suite.push.subject:mailto:support@lezitech.site}") String subject) {
        this.db = db;
        this.jdbc = jdbc;
        this.box = box;
        this.configuredPublic = configuredPublic;
        this.configuredPrivate = configuredPrivate;
        this.subject = subject;
    }

    @PostConstruct
    void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** The key browsers subscribe with; made on first use. Null if push can't run (e.g. no SECRETS_KEY on a real database). */
    public String publicKey() {
        ready();
        return publicKey;
    }

    private synchronized boolean ready() {
        if (push != null) {
            return true;
        }
        try {
            String pub = configuredPublic;
            String priv = configuredPrivate;
            if (pub == null || pub.isBlank()) {
                if (!box.ready()) {
                    log.warn("Web Push is off: set SECRETS_KEY (or VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY)");
                    return false;
                }
                pub = null;
                priv = null;
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT name, value FROM platform_secrets WHERE name IN ('vapid.public', 'vapid.private')");
                for (Map<String, Object> r : rows) {
                    if ("vapid.public".equals(r.get("name"))) {
                        pub = (String) r.get("value");
                    } else {
                        priv = box.decrypt((String) r.get("value"));
                    }
                }
                if (pub == null || priv == null) {
                    String[] made = makeKeys();
                    jdbc.update("INSERT INTO platform_secrets (name, value) VALUES ('vapid.public', ?), ('vapid.private', ?) ON CONFLICT (name) DO NOTHING",
                            made[0], box.encrypt(made[1]));
                    // Another server may have made them at the same moment: use whatever was saved.
                    pub = jdbc.queryForObject("SELECT value FROM platform_secrets WHERE name = 'vapid.public'", String.class);
                    priv = box.decrypt(jdbc.queryForObject("SELECT value FROM platform_secrets WHERE name = 'vapid.private'", String.class));
                }
            }
            push = new PushService(pub, priv, subject);
            publicKey = pub;
            return true;
        } catch (Exception e) {
            log.warn("Web Push couldn't start: {}", e.getMessage(), e);
            return false;
        }
    }

    /** A P-256 key pair, base64url: the public key uncompressed (65 bytes), the private key's 32-byte number. */
    static String[] makeKeys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        g.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"), new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] pub = ((ECPublicKey) kp.getPublic()).getQ().getEncoded(false);
        byte[] d = toFixed(((ECPrivateKey) kp.getPrivate()).getD(), 32);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return new String[]{enc.encodeToString(pub), enc.encodeToString(d)};
    }

    private static byte[] toFixed(BigInteger n, int size) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[size];
        int copy = Math.min(raw.length, size);
        System.arraycopy(raw, raw.length - copy, out, size - copy, copy);
        return out;
    }

    // ---------------- devices ----------------

    public void subscribe(String endpoint, String p256dh, String auth, String device) {
        db.exec("""
                INSERT INTO push_subscriptions (tenant_id, user_id, endpoint, p256dh, auth, device)
                VALUES (:t, :u, :e, :p, :a, :d)
                ON CONFLICT (endpoint) DO UPDATE SET user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth,
                                                     device = excluded.device""",
                "e", endpoint, "p", p256dh, "a", auth, "d", device);
    }

    public void unsubscribe(String endpoint) {
        db.exec("DELETE FROM push_subscriptions WHERE endpoint = :e AND user_id = :u", "e", endpoint);
    }

    public long devices(UUID userId) {
        return db.value("SELECT count(*) FROM push_subscriptions WHERE user_id = :id", Long.class, "id", userId);
    }

    // ---------------- sending ----------------

    /** To every active staff member in these jobs (the owner always), on each device they turned notifications on. */
    public void toStaff(Collection<String> roles, String title, String body, String url, String tag) {
        List<String> r = new ArrayList<>(roles);
        r.add("OWNER");
        send(db.list("""
                SELECT p.endpoint, p.p256dh, p.auth FROM push_subscriptions p JOIN users u ON u.id = p.user_id
                WHERE u.active AND u.role IN (:roles)""", "roles", r), title, body, url, tag);
    }

    public void toUser(UUID userId, String title, String body, String url, String tag) {
        if (userId == null) {
            return;
        }
        send(db.list("SELECT endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = :id", "id", userId), title, body, url, tag);
    }

    private void send(List<Map<String, Object>> subs, String title, String body, String url, String tag) {
        if (subs.isEmpty()) {
            return;
        }
        UUID tenant = TenantContext.tenantId();
        String payload = "{\"title\":" + q(title) + ",\"body\":" + q(body) + ",\"url\":" + q(url) + ",\"tag\":" + q(tag) + "}";
        Runnable work = () -> sender.submit(() -> {
            if (!ready()) {
                return;
            }
            for (Map<String, Object> s : subs) {
                String endpoint = (String) s.get("endpoint");
                try {
                    var res = push.send(new Notification(new Subscription(endpoint,
                            new Subscription.Keys((String) s.get("p256dh"), (String) s.get("auth"))), payload));
                    int code = res.getStatusLine().getStatusCode();
                    if (code == 404 || code == 410) {
                        TenantContext.runAs(tenant, null, () -> jdbc.update(
                                "DELETE FROM push_subscriptions WHERE endpoint = ? AND tenant_id = ?", endpoint, tenant));
                    }
                } catch (Exception e) {
                    log.debug("Push to a device failed: {}", e.getMessage());
                }
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
