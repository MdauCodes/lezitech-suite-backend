package com.lezitech.suite;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

@SpringBootApplication
public class SuiteApplication {

    public static void main(String[] args) throws Exception {
        startEmbeddedDatabaseIfLocal();
        SpringApplication.run(SuiteApplication.class, args);
    }

    /**
     * Local runs get a real Postgres on port 54329 with data kept in .localdb, so nobody
     * needs Docker or a system Postgres password. Production sets DATABASE_URL and
     * EMBEDDED_DB=false and this does nothing.
     */
    private static void startEmbeddedDatabaseIfLocal() throws Exception {
        String enabled = System.getenv().getOrDefault("EMBEDDED_DB", "true");
        if (!"true".equalsIgnoreCase(enabled) || System.getenv("DATABASE_URL") != null) {
            return;
        }
        String dir = System.getenv().getOrDefault("EMBEDDED_DB_DIR", ".localdb");
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(54329)
                .setDataDirectory(Path.of(dir).toAbsolutePath())
                .setCleanDataDirectory(false)
                .start();
        try (Connection c = pg.getPostgresDatabase().getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT 1 FROM pg_database WHERE datname = 'suite'")) {
            if (!rs.next()) {
                c.createStatement().execute("CREATE DATABASE suite");
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));
    }
}
