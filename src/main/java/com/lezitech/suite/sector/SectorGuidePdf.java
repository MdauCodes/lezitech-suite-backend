package com.lezitech.suite.sector;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The guide a visitor downloads from a sector's page on the website: what Busynez does for that trade,
 * in plain words, to read on a phone with no internet.
 *
 * Nothing here is written per sector. Every fact comes from the sector pack (sectors.json / subsectors.json):
 * its name, tagline, the screens it gets (modules), its own words (terms), what it counts (kpis), what it
 * switches on (features) and, for a sub-sector, its seo headline and pitch. The wording that is the same for
 * every trade lives in guide.json. So a change to a pack changes its guide, and no document drifts.
 *
 * There are two papers, the same guide either way. A5 is the one offered first: on a phone it fills the
 * screen, so the text is readable without pinching. A4 is for an owner printing copies for staff - it is
 * the same page blown up, type and margins together, so the words break in the same places and nothing
 * comes out as small print.
 */
final class SectorGuidePdf {

    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font PLAIN = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font ITALIC = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
    /** Everything below is written in A5 points; on A4 the whole page is multiplied by this. */
    private static final float A5_MARGIN = 36;

    private final PDDocument doc = new PDDocument();
    private final float[] brand;
    private final PDRectangle paper;
    private final float k;
    private final float margin;
    private final float width;
    private PDPageContentStream out;
    private float y;
    private int pageNo;

    private SectorGuidePdf(String colour, boolean a4) {
        this.brand = rgb(colour);
        this.paper = a4 ? PDRectangle.A4 : PDRectangle.A5;
        this.k = paper.getWidth() / PDRectangle.A5.getWidth();
        this.margin = A5_MARGIN * k;
        this.width = paper.getWidth() - 2 * margin;
    }

    /** Down the page by a distance written in A5 points. */
    private void down(float v) {
        y -= v * k;
    }

    /** "#c2410c" as the three parts PDFBox wants; a quiet grey if the pack has no colour. */
    private static float[] rgb(String hex) {
        try {
            String h = hex.replace("#", "").trim();
            return new float[]{
                    Integer.parseInt(h.substring(0, 2), 16) / 255f,
                    Integer.parseInt(h.substring(2, 4), 16) / 255f,
                    Integer.parseInt(h.substring(4, 6), 16) / 255f};
        } catch (RuntimeException e) {
            return new float[]{0.2f, 0.2f, 0.2f};
        }
    }

    /**
     * Only characters the built-in fonts can draw. Accented letters lose their accent ("cafés" stays
     * "cafes") rather than turning into a question mark, and the typographer's quotes and dashes that
     * come out of a text editor become the plain ones.
     */
    private static String safe(String s) {
        String flat = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replace('…', '.').replace(' ', ' ');
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            b.append(c >= 32 && c < 127 ? c : ' ');
        }
        return b.toString();
    }

    /** The trade's own words put into a line of copy: "{items}" becomes "Menu", "{item|lower}" becomes "menu". */
    private static String words(String text, JsonNode terms) {
        String s = text;
        for (Iterator<String> it = terms.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            String value = terms.get(key).asText();
            s = s.replace("{" + key + "}", value)
                    .replace("{" + key + "|lower}", value.toLowerCase(Locale.ROOT));
        }
        // Anything the pack has no word for falls back to the plain English in the braces.
        return s.replaceAll("\\{([a-zA-Z]+)\\|lower\\}", "$1").replaceAll("\\{([a-zA-Z]+)\\}", "$1");
    }

    private void page() throws IOException {
        if (out != null) {
            footer();
            out.close();
        }
        PDPage p = new PDPage(paper);
        doc.addPage(p);
        out = new PDPageContentStream(doc, p);
        pageNo++;
        y = paper.getHeight() - margin;
    }

    /** The same quiet line on the foot of every page, so a printed page still says where it came from. */
    private void footer() throws IOException {
        out.setNonStrokingColor(0.45f, 0.45f, 0.45f);
        out.beginText();
        out.setFont(PLAIN, 7.5f * k);
        out.newLineAtOffset(margin, margin - 16 * k);
        out.showText(safe("Busynez by LeziTech   busynez.site   page " + pageNo));
        out.endText();
        out.setNonStrokingColor(0f, 0f, 0f);
    }

    private void text(PDType1Font f, float size, String s, float x) throws IOException {
        out.beginText();
        out.setFont(f, size * k);
        out.newLineAtOffset(x, y);
        out.showText(safe(s));
        out.endText();
    }

    private List<String> wrap(PDType1Font f, float size, String s, float room) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : safe(s).split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            String next = cur.isEmpty() ? w : cur + " " + w;
            if (f.getStringWidth(next) / 1000 * size * k > room && !cur.isEmpty()) {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur = new StringBuilder(next);
            }
        }
        if (!cur.isEmpty()) {
            lines.add(cur.toString());
        }
        return lines;
    }

    /** A paragraph, wrapped to the page width, starting at an indent. */
    private void para(PDType1Font f, float size, String s, float indent, float gap) throws IOException {
        for (String l : wrap(f, size, s, width - indent * k)) {
            need(size + 3);
            text(f, size, l, margin + indent * k);
            down(size + 3);
        }
        down(gap);
    }

    private void para(String s) throws IOException {
        para(PLAIN, 10, s, 0, 6);
    }

    private void need(float h) throws IOException {
        if (y - h * k < margin + 6 * k) {
            page();
        }
    }

    /** A section heading in the trade's own colour, with a rule under it. */
    private void heading(String s) throws IOException {
        need(52);
        down(10);
        out.setNonStrokingColor(brand[0], brand[1], brand[2]);
        text(BOLD, 12.5f, s, margin);
        down(6);
        out.setStrokingColor(brand[0], brand[1], brand[2]);
        out.setLineWidth(0.8f * k);
        out.moveTo(margin, y);
        out.lineTo(margin + width, y);
        out.stroke();
        out.setNonStrokingColor(0f, 0f, 0f);
        down(13);
    }

    /** A bullet: the dot stays put and the words wrap under themselves. */
    private void bullet(String s) throws IOException {
        need(16);
        text(PLAIN, 10, "-", margin);
        para(PLAIN, 10, s, 12, 2);
    }

    /** A numbered step, so "step 3" means something when somebody rings us about it. */
    private void step(int n, String s) throws IOException {
        need(18);
        out.setNonStrokingColor(brand[0], brand[1], brand[2]);
        text(BOLD, 10, n + ".", margin);
        out.setNonStrokingColor(0f, 0f, 0f);
        para(PLAIN, 10, s, 16, 4);
    }

    /** A named thing and what it is for: the name in bold, the sentence under it. */
    private void named(String name, String what) throws IOException {
        need(26);
        text(BOLD, 10, name, margin);
        down(13);
        para(PLAIN, 9.5f, what, 10, 5);
    }

    /** "eating in, takeaway and delivery": a list the way a person says it out loud. */
    private static String list(JsonNode array, Map<String, String> plain) {
        List<String> parts = new ArrayList<>();
        array.forEach(n -> parts.add(plain.getOrDefault(n.asText(), n.asText().toLowerCase(Locale.ROOT).replace('_', ' '))));
        if (parts.size() < 2) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /**
     * The guide for one sector pack.
     *
     * @param pack       the pack as the public sees it (SectorCatalog.publicView), already personalised if a
     *                   sub-sector was asked for
     * @param copy       guide.json: the wording shared by every trade
     * @param comingSoon true for the packs that cannot be signed up for yet (pharmacies, resorts)
     * @param a4         true for the bigger paper an owner prints for staff; false for the phone-sized one
     */
    static byte[] render(JsonNode pack, JsonNode copy, boolean comingSoon, boolean a4) {
        String sub = pack.path("subSector").asText("");
        String trade = sub.isEmpty() ? pack.path("name").asText("your business") : sub;
        JsonNode terms = pack.path("terms");
        SectorGuidePdf p = new SectorGuidePdf(pack.path("color").asText("#1f2937"), a4);
        try {
            p.page();

            // The cover, on the same page as the start of the guide: nobody wants to swipe past an empty page.
            p.out.setNonStrokingColor(p.brand[0], p.brand[1], p.brand[2]);
            p.text(BOLD, 11, "BUSYNEZ", p.margin);
            p.out.setNonStrokingColor(0f, 0f, 0f);
            p.down(14);
            p.text(PLAIN, 9.5f, "A guide for " + trade.toLowerCase(Locale.ROOT), p.margin);
            p.down(26);
            p.para(BOLD, 19, sub.isEmpty() ? pack.path("name").asText("") : sub, 0, 4);
            String strap = pack.path("seo").path("headline").asText("");
            p.para(ITALIC, 11, strap.isEmpty() ? pack.path("tagline").asText("") : strap, 0, 10);
            String pitch = pack.path("seo").path("pitch").asText("");
            if (!pitch.isEmpty()) {
                p.para(pitch);
            }
            if (comingSoon) {
                p.heading("This one is on the way");
                for (JsonNode line : copy.path("comingSoon")) {
                    p.para(line.asText());
                }
            }

            p.heading("What Busynez is");
            for (JsonNode line : copy.path("intro")) {
                p.para(line.asText());
            }

            // What this trade is given. The pack decides; the guide only reads it.
            p.heading("The screens you get");
            p.para(copy.path("screensIntro").asText());
            for (JsonNode m : pack.path("modules")) {
                JsonNode mod = copy.path("modules").path(m.asText());
                if (mod.isMissingNode()) {
                    continue;
                }
                p.named(words(mod.path("label").asText(), terms), words(mod.path("what").asText(), terms));
            }

            if (terms.isObject() && !terms.isEmpty()) {
                p.heading("What things are called here");
                p.para(copy.path("wordsIntro").asText());
                // The pack's own word, then the plain English for it. The last part is written out rather
                // than built from the key, so no sentence ends up saying "a item" or "a staff".
                for (String[] word : new String[][]{{"customer", "customers", "a customer"},
                        {"item", "items", "an item"}, {"order", "orders", "an order"},
                        {"staff", null, "staff"}, {"location", null, "a place"}}) {
                    String one = terms.path(word[0]).asText("");
                    String many = word[1] == null ? "" : terms.path(word[1]).asText("");
                    if (one.isEmpty()) {
                        continue;
                    }
                    p.bullet((many.isEmpty() ? one : one + " and " + many)
                            + " - what another system would call " + word[2] + ".");
                }
            }

            JsonNode kpis = pack.path("kpis");
            if (kpis.isArray() && !kpis.isEmpty()) {
                p.heading("The numbers you see first");
                p.para(copy.path("numbersIntro").asText());
                for (JsonNode k : kpis) {
                    String label = copy.path("kpis").path(k.asText()).asText("");
                    if (!label.isEmpty()) {
                        p.bullet(words(label, terms));
                    }
                }
            }

            JsonNode features = pack.path("features");
            List<String> switches = new ArrayList<>();
            for (Iterator<String> it = features.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                JsonNode v = features.get(key);
                String line = copy.path("features").path(key).asText("");
                if (v.isBoolean() && v.asBoolean() && !line.isEmpty()) {
                    switches.add(words(line, terms));
                }
            }
            JsonNode channels = features.path("channels");
            if (channels.isArray() && !channels.isEmpty()) {
                Map<String, String> plain = new java.util.LinkedHashMap<>();
                copy.path("channels").fields().forEachRemaining(e -> plain.put(e.getKey(), e.getValue().asText()));
                switches.add(channels.size() == 1
                        ? "Selling " + list(channels, plain) + "."
                        : "Selling for " + list(channels, plain) + ", each kept apart in the reports.");
            }
            if (!switches.isEmpty()) {
                p.heading("What it switches on for you");
                p.para(copy.path("switchesIntro").asText());
                for (String s : switches) {
                    p.bullet(s);
                }
            }

            // The trades inside this pack, so a baker reading the restaurants guide sees herself in it.
            JsonNode subs = pack.path("subSectorDetails");
            if (sub.isEmpty() && subs.isArray() && !subs.isEmpty()) {
                p.heading("Businesses like yours");
                p.para("This same pack is used by all of these, each one set up a little differently:");
                for (JsonNode s : subs) {
                    String name = s.path("name").asText();
                    String tag = s.path("tagline").asText("");
                    boolean soon = s.path("comingSoon").asBoolean(false);
                    p.bullet(name + (tag.isEmpty() ? "" : " - " + tag.toLowerCase(Locale.ROOT))
                            + (soon ? " (not open yet)" : ""));
                }
            }

            if (comingSoon) {
                p.heading("How to be told when it opens");
                for (JsonNode line : copy.path("comingSoon")) {
                    p.para(line.asText());
                }
            } else {
                p.heading("How to start");
                int n = 1;
                for (JsonNode line : copy.path("start")) {
                    p.step(n++, line.asText());
                }
            }

            p.heading("What it asks of you");
            for (JsonNode line : copy.path("effort")) {
                p.bullet(line.asText());
            }

            p.heading("Reach us");
            JsonNode contact = copy.path("contact");
            p.bullet("WhatsApp: " + contact.path("whatsapp").asText());
            p.bullet("Call: " + contact.path("call").asText());
            p.bullet("Email: " + contact.path("email").asText());
            p.bullet("Online: " + contact.path("website").asText());
            p.down(4);
            p.para(ITALIC, 10, copy.path("closing").asText(), 0, 0);

            p.footer();
            p.out.close();
            p.out = null;
            // An empty outline keeps phone PDF readers from showing a broken bookmark panel.
            p.doc.getDocumentCatalog().setDocumentOutline(new PDDocumentOutline());
            p.doc.getDocumentInformation().setTitle("Busynez - a guide for " + safe(trade.toLowerCase(Locale.ROOT)));
            p.doc.getDocumentInformation().setAuthor("LeziTech");
            p.doc.getDocumentInformation().setSubject("What Busynez does for " + safe(trade.toLowerCase(Locale.ROOT)) + " in Kenya");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            p.doc.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("The guide could not be made", e);
        } finally {
            try {
                if (p.out != null) {
                    p.out.close();
                }
                p.doc.close();
            } catch (IOException ignored) {
                // nothing useful left to do
            }
        }
    }
}
