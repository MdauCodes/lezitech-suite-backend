package com.lezitech.suite;

import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spreadsheet import with or without photo links: a bad or private link never stops the import. */
class ImportPhotosTest extends IntegrationTest {

    @Test
    void photosAreOptionalAndPrivateLinksAreRefused() {
        String owner = token(signup("retail"));
        Res plain = post("/items/import", owner, Map.of("rows", List.of(Map.of("name", "Zz Test Rice", "price", 180))));
        assertThat(plain.status()).isEqualTo(200);
        assertThat(plain.at("/created").asInt()).isEqualTo(1);
        assertThat(plain.at("/photos").asInt()).isZero();

        Res linked = post("/items/import", owner, Map.of("rows", List.of(
                Map.of("name", "Zz Test Salt", "price", 40, "photo", "http://127.0.0.1:8090/secret.png"),
                Map.of("name", "Zz Test Tea", "price", 120, "photo", "ftp://example.com/x.png"))));
        assertThat(linked.status()).isEqualTo(200);
        assertThat(linked.at("/created").asInt()).isEqualTo(2);
        assertThat(linked.at("/photos").asInt()).isZero();
        assertThat(linked.at("/photoProblems").toString()).contains("private address").contains("https://");
    }
}
