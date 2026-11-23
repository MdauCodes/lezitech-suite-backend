package com.lezitech.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.sector.SectorGuideController;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guide a visitor downloads from a sector's page. It needs no database and no sign-in, so this reads the
 * real packs, makes the real PDFs and reads the words back out of them.
 */
class SectorGuideTest {

    private final SectorGuideController guides;

    SectorGuideTest() throws IOException {
        ObjectMapper json = new ObjectMapper();
        guides = new SectorGuideController(new SectorCatalog(json), json);
    }

    /** The same words, however the lines were broken and wherever the page feet fell. */
    private static String flat(String text) {
        return text.replaceAll("Busynez by LeziTech\\s+busynez.site\\s+page \\d+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String words(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(0);
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void everyTradeHasAGuideThatReadsLikeAGuide() throws IOException {
        for (String key : new String[]{"restaurant", "hotel", "retail", "beauty", "wholesale",
                "pharmacy", "services", "tours", "property", "resort"}) {
            ResponseEntity<byte[]> res = guides.sector(key, null);
            assertThat(res.getStatusCode().value()).as(key).isEqualTo(200);
            assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .as(key).contains("busynez-guide-" + key + ".pdf");
            byte[] body = res.getBody();
            assertThat(body).as(key).isNotNull();
            String text = words(body);
            assertThat(text).as(key)
                    .contains("BUSYNEZ")
                    .contains("What Busynez is")
                    .contains("The screens you get")
                    .contains("What it asks of you")
                    .contains("Reach us")
                    .contains("+254 796 814 154");
            // Plain words only, and no prices: both are the owner's rules.
            assertThat(text).as(key).doesNotContain("KES").doesNotContain("comingSoon").doesNotContain("{");
            assertThat(text.length()).as(key).isGreaterThan(2000);
        }
    }

    @Test
    void theGuideSpeaksTheTradesOwnWords() throws IOException {
        String restaurant = words(guides.sector("restaurant", null).getBody());
        assertThat(restaurant).contains("Tables").contains("Kitchen screen").contains("Menu")
                .contains("Guests").contains("How to start");

        String property = words(guides.sector("property", null).getBody());
        assertThat(property).contains("Rent and tenants").contains("Meter readings").contains("Units rented out");
        assertThat(property).doesNotContain("Kitchen screen");
    }

    @Test
    void aTradeThatIsNotOpenYetSaysSoInsteadOfSelling() throws IOException {
        for (String soon : new String[]{"pharmacy", "resort"}) {
            String text = words(guides.sector(soon, null).getBody());
            assertThat(text).as(soon)
                    .contains("This one is on the way")
                    .contains("How to be told when it opens")
                    .doesNotContain("How to start")
                    .doesNotContain("Start free");
        }
    }

    @Test
    void aKindOfBusinessInsideATradeGetsItsOwnGuide() throws IOException {
        ResponseEntity<byte[]> res = guides.subSector("retail", "minimart", null);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("busynez-guide-retail-minimart.pdf");
        String text = words(res.getBody());
        assertThat(text).contains("Minimart").contains("Reach us");

        // A sub-sector nobody has heard of falls back to the trade's guide rather than failing.
        assertThat(words(guides.subSector("retail", "nonesuch", null).getBody())).contains("Shops");
    }

    @Test
    void anUnknownTradeIsToldPlainlyThatItIsUnknown() {
        assertThatThrownBy(() -> guides.sector("nonesuch", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown sector");
        assertThatThrownBy(() -> guides.sector("../../etc/passwd", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theSameGuideComesOnBiggerPaperForPrinting() throws IOException {
        ResponseEntity<byte[]> print = guides.sector("restaurant", "a4");
        assertThat(print.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("busynez-guide-restaurant-to-print.pdf");
        try (PDDocument a4 = Loader.loadPDF(print.getBody());
             PDDocument phone = Loader.loadPDF(guides.sector("restaurant", null).getBody())) {
            // The same page blown up: same words, same pages, bigger paper.
            assertThat(a4.getPage(0).getMediaBox().getWidth())
                    .isGreaterThan(phone.getPage(0).getMediaBox().getWidth());
            assertThat(a4.getNumberOfPages()).isEqualTo(phone.getNumberOfPages());
            assertThat(flat(new PDFTextStripper().getText(a4))).isEqualTo(flat(new PDFTextStripper().getText(phone)));
        }
    }

    @Test
    void theSameGuideIsOnlyMadeOnce() {
        assertThat(guides.sector("hotel", null).getBody()).isSameAs(guides.sector("hotel", null).getBody());
    }
}
