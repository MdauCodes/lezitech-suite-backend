package com.lezitech.suite.platform;

import com.lezitech.suite.common.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lets an open app ask whether it must reload, and lets LeziTech say so. */
@RestController
public class ClientReleaseController {

    private final PlatformSettings settings;
    private final byte[] operatorKey;
    private final String builtRelease;

    /** Used only when there is no operator override and no build info (tests run from classes, not a built jar). */
    static final String FALLBACK_RELEASE = "20260101000000";

    public ClientReleaseController(PlatformSettings settings, @Value("${suite.operator-key}") String operatorKey,
                                   ObjectProvider<BuildProperties> build) {
        this.settings = settings;
        BuildProperties bp = build.getIfAvailable();
        this.builtRelease = bp == null || bp.getTime() == null ? null
                : DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(bp.getTime());
        this.operatorKey = operatorKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/public/client-status")
    public Map<String, Object> status(@RequestParam(required = false) String release) {
        boolean valid = PlatformSettings.isStamp(release);
        String min = settings.minClientRelease();
        String latest = effectiveLatest();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mustReload", valid && min != null && release.compareTo(min) < 0);
        out.put("mayReload", false); // the browser decides whether to offer a reload
        out.put("message", settings.reloadMessage());
        out.put("latestRelease", latest);
        out.put("checkAgainSeconds", 60);
        return out;
    }

    /** Operator override, else the time this server was built, else a fixed fallback. Never null; never client-supplied. */
    private String effectiveLatest() {
        String over = settings.latestClientRelease();
        if (over != null) {
            return over;
        }
        return builtRelease != null ? builtRelease : FALLBACK_RELEASE;
    }

    @PostMapping("/api/platform/client-release")
    public Map<String, Object> set(@RequestHeader(value = "X-Operator-Key", required = false) String key,
                                   @RequestBody Map<String, Object> body) {
        guard(key);
        settings.setClientRelease(text(body.get("latest")), text(body.get("min")), text(body.get("message")), "operator");
        return Map.of("ok", true);
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void guard(String key) {
        if (key == null || !MessageDigest.isEqual(operatorKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Operator key required");
        }
    }
}
