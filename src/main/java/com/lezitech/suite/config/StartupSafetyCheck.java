package com.lezitech.suite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start a server that talks to a real database with the development secrets that
 * ship in application.yml. Anyone holding the default JWT secret could sign tokens for any
 * workspace; anyone holding the default operator key could activate plans. Without SECRETS_KEY,
 * businesses' payment keys couldn't be stored encrypted.
 * Local runs on the embedded database are exempt.
 */
@Component
public class StartupSafetyCheck {

    public static final String DEV_JWT_SECRET = "local-dev-secret-change-me-before-any-real-deploy-0123456789";
    public static final String DEV_OPERATOR_KEY = "local-operator-key";

    public StartupSafetyCheck(@Value("${suite.jwt.secret}") String jwtSecret, @Value("${suite.operator-key}") String operatorKey,
                              @Value("${suite.secrets-key:}") String secretsKey, @Value("${suite.files.store:disk}") String filesStore) {
        boolean embedded = System.getenv("DATABASE_URL") == null && !"false".equalsIgnoreCase(System.getenv("EMBEDDED_DB"));
        verify(embedded, jwtSecret, operatorKey, secretsKey);
        // Live businesses' photos and ETRs must survive a redeploy: they go to R2, never the container's disk.
        if (!embedded && !"r2".equalsIgnoreCase(filesStore)) {
            throw new IllegalStateException("Refusing to start with unsafe settings: FILES_STORE must be r2 (with R2_ACCOUNT_ID, R2_ACCESS_KEY, R2_SECRET_KEY, R2_BUCKET)");
        }
    }

    public static void verify(boolean embedded, String jwtSecret, String operatorKey, String secretsKey) {
        if (embedded) {
            return;
        }
        List<String> problems = new ArrayList<>();
        if (jwtSecret == null || jwtSecret.length() < 64 || DEV_JWT_SECRET.equals(jwtSecret)) {
            problems.add("JWT_SECRET must be set to a random value of at least 64 characters (not the development default)");
        }
        if (operatorKey == null || operatorKey.isBlank() || DEV_OPERATOR_KEY.equals(operatorKey) || operatorKey.length() < 24) {
            problems.add("OPERATOR_KEY must be set to a random value of at least 24 characters (not the development default)");
        }
        if (secretsKey == null || secretsKey.isBlank()) {
            problems.add("SECRETS_KEY must be set (openssl rand -base64 32) so payment keys are stored encrypted");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with unsafe settings: " + String.join("; ", problems));
        }
    }
}
