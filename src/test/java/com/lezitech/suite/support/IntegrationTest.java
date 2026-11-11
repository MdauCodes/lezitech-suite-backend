package com.lezitech.suite.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Boots the real app over HTTP against its own throwaway Postgres (same engine and RLS as
 * production), so tests exercise the whole stack: JWT, role checks, RLS, transactions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().setDataDirectory(Files.createTempDirectory("suite-test-pg")).setCleanDataDirectory(true).start();
            try (Connection c = PG.getPostgresDatabase().getConnection()) {
                c.createStatement().execute("CREATE DATABASE suite");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not start test Postgres", e);
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        String url = "jdbc:postgresql://127.0.0.1:" + PG.getPort() + "/suite";
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> "suite_app");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.flyway.url", () -> url);
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "");
        r.add("suite.demo.enabled", () -> "false");
        r.add("suite.login.max-failures", () -> "5");
        r.add("suite.mpesa.mode", () -> "simulate");
        r.add("suite.etims.mode", () -> "stub");
        r.add("suite.email.provider", () -> "log");
        r.add("suite.email.retry-seconds", () -> "0");
        r.add("suite.files.store", () -> "disk");
        r.add("suite.files.dir", () -> "target/test-files");
        r.add("suite.email.jobs-every-ms", () -> "300");
        r.add("suite.mpesa.simulate-delay-ms", () -> "300");
        r.add("suite.mpesa.c2b-secret", () -> "test-c2b-secret");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    public record Res(int status, JsonNode body) {
        public JsonNode at(String pointer) {
            return body.at(pointer);
        }
    }

    protected Res call(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                    .header("Content-Type", "application/json");
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            HttpResponse<String> res = http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = res.body() == null || res.body().isBlank() ? json.nullNode() : json.readTree(res.body());
            return new Res(res.statusCode(), node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Upload one file as multipart form field "file". */
    protected Res upload(String path, String token, byte[] bytes, String filename) {
        try {
            String boundary = "----suite" + UUID.randomUUID();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
            out.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = res.body() == null || res.body().isBlank() ? json.nullNode() : json.readTree(res.body());
            return new Res(res.statusCode(), node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Raw(int status, String contentType, byte[] bytes) {
    }

    /** GET returning the raw bytes (files, photos). */
    protected Raw raw(String path, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path)).GET();
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Raw(res.statusCode(), res.headers().firstValue("Content-Type").orElse(""), res.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Res get(String path, String token) {
        return call("GET", path, token, null);
    }

    protected Res post(String path, String token, Object body) {
        return call("POST", path, token, body == null ? Map.of() : body);
    }

    /** A fresh workspace with sample data; returns the owner's session JSON. */
    protected JsonNode signup(String sector) {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Test " + sector + " " + email.substring(6, 10), "sector", sector,
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        if (r.status() != 200) {
            throw new IllegalStateException("Signup failed: " + r.body());
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) r.body()).put("email", email);
        return r.body();
    }

    protected static String token(JsonNode session) {
        return session.get("token").asText();
    }

    /** Add a staff member with a role (and PIN 1234) and sign in as them. */
    protected String staff(String ownerToken, String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res add = post("/team", ownerToken, Map.of("name", role + " person", "email", email, "password", "password123", "role", role, "pin", "1234"));
        if (add.status() != 200) {
            throw new IllegalStateException("Add staff failed: " + add.body());
        }
        return post("/auth/login", null, Map.of("email", email, "password", "password123")).body().get("token").asText();
    }

    protected String userId(String ownerToken, String role) {
        for (JsonNode u : get("/team", ownerToken).body()) {
            if (role.equals(u.get("role").asText())) {
                return u.get("id").asText();
            }
        }
        throw new IllegalStateException("No " + role);
    }

    protected JsonNode item(String token, String nameContains) {
        for (JsonNode i : get("/items", token).body()) {
            if (i.get("name").asText().contains(nameContains)) {
                return i;
            }
        }
        throw new IllegalStateException("No item " + nameContains);
    }

    protected static List<Map<String, Object>> line(String itemId, int qty) {
        return List.of(Map.of("itemId", itemId, "qty", qty));
    }
}
