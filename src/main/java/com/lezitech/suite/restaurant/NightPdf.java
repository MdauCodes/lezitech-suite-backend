package com.lezitech.suite.restaurant;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The night summary as a simple A4 PDF the owner can download, keep or print. */
final class NightPdf {

    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font PLAIN = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final float MARGIN = 50;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

    private final PDDocument doc = new PDDocument();
    private PDPageContentStream out;
    private float y;

    private NightPdf() {
    }

    private static BigDecimal n(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static String kes(Object o) {
        return "KES " + String.format("%,d", n(o).setScale(0, RoundingMode.HALF_UP).longValue());
    }

    /** Only characters the built-in font can draw. */
    private static String safe(PDType1Font f, String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            String c = String.valueOf(s.charAt(i));
            try {
                f.encode(c);
                b.append(c);
            } catch (Exception e) {
                b.append(c.equals("’") ? "'" : "?");
            }
        }
        return b.toString();
    }

    private void page() throws IOException {
        if (out != null) {
            out.close();
        }
        PDPage p = new PDPage(PDRectangle.A4);
        doc.addPage(p);
        out = new PDPageContentStream(doc, p);
        y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private void text(PDType1Font f, float size, String s, float x) throws IOException {
        out.beginText();
        out.setFont(f, size);
        out.newLineAtOffset(x, y);
        out.showText(safe(f, s));
        out.endText();
    }

    /** A paragraph, wrapped to the page width. */
    private void para(PDType1Font f, float size, String s) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : safe(f, s).split(" ")) {
            String next = cur.isEmpty() ? w : cur + " " + w;
            if (f.getStringWidth(next) / 1000 * size > WIDTH && !cur.isEmpty()) {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur = new StringBuilder(next);
            }
        }
        if (!cur.isEmpty()) {
            lines.add(cur.toString());
        }
        for (String l : lines) {
            need(size + 4);
            text(f, size, l, MARGIN);
            y -= size + 4;
        }
    }

    private void need(float h) throws IOException {
        if (y - h < MARGIN) {
            page();
        }
    }

    private void heading(String s) throws IOException {
        y -= 8;
        need(24);
        text(BOLD, 12, s, MARGIN);
        y -= 18;
    }

    /** A label on the left and its value on the right. */
    private void row(String label, String value) throws IOException {
        need(16);
        text(PLAIN, 10, label, MARGIN);
        String v = safe(PLAIN, value);
        float w = PLAIN.getStringWidth(v) / 1000 * 10;
        text(PLAIN, 10, v, Math.max(MARGIN + 200, MARGIN + WIDTH - w));
        y -= 15;
    }

    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> s) {
        NightPdf p = new NightPdf();
        try {
            p.page();
            p.text(BOLD, 18, String.valueOf(s.get("business")), MARGIN);
            p.y -= 22;
            p.text(PLAIN, 12, "Your day: " + s.get("dayLabel"), MARGIN);
            p.y -= 22;
            p.para(BOLD, 11, String.valueOf(s.get("headline")));

            Map<String, Object> sales = (Map<String, Object>) s.get("sales");
            p.heading("Sales");
            p.row("Total", kes(sales.get("total")));
            p.row("Bills", String.valueOf(sales.get("bills")));
            p.row("Average bill", kes(sales.get("average")));
            for (Map<String, Object> m : (List<Map<String, Object>>) sales.get("byMethod")) {
                p.row("   " + NightSummary.methodName((String) m.get("method")), kes(m.get("amount")));
            }
            if (n(sales.get("refunds")).signum() > 0) {
                p.row("Money given back", kes(sales.get("refunds")));
            }
            if (((Number) sales.get("openBills")).longValue() > 0) {
                p.row("Bills still open", sales.get("openBills") + " (" + kes(sales.get("openUnpaid")) + " not paid yet)");
            }

            List<Map<String, Object>> tills = (List<Map<String, Object>>) s.get("tills");
            if (!tills.isEmpty()) {
                p.heading("Tills");
                for (Map<String, Object> t : tills) {
                    String what = Boolean.TRUE.equals(t.get("open")) ? "still open" : t.get("difference") == null ? "not counted"
                            : n(t.get("difference")).signum() == 0 ? "all there"
                            : n(t.get("difference")).signum() < 0 ? "short " + kes(n(t.get("difference")).negate()) : "over " + kes(t.get("difference"));
                    p.row(String.valueOf(t.get("cashier")), "expected " + kes(t.get("expectedCash")) + ", " + what);
                }
            }
            for (Map<String, Object> x : (List<Map<String, Object>>) s.get("staffShort")) {
                p.row("On " + x.get("name"), kes(x.get("amount")) + " (short or unpaid bills)");
            }

            p.heading("Food, waste and counts");
            if (s.get("foodCostPct") != null) {
                p.row("Food cost", n(s.get("foodCostPct")).stripTrailingZeros().toPlainString() + "% of food sales");
            }
            Map<String, Object> waste = (Map<String, Object>) s.get("waste");
            p.row("Waste", kes(waste.get("value")) + " at cost (" + waste.get("entries") + " entries)");
            for (Map<String, Object> c : (List<Map<String, Object>>) s.get("countsShort")) {
                p.para(PLAIN, 10, "- " + c.get("summary"));
            }

            Map<String, Object> v = (Map<String, Object>) s.get("voidsAndDiscounts");
            p.heading("Cancelled bills, discounts and changes");
            p.row("Cancelled bills", v.get("voids") + " (" + kes(v.get("voidValue")) + ")");
            p.row("Discounts and price changes", v.get("discounts") + " (" + kes(v.get("discountValue")) + " given up)");
            p.row("Items taken off after printing or cooking", v.get("removed") + " (" + kes(v.get("removedValue")) + ")");
            p.row("Refunds", String.valueOf(v.get("refunds")));

            List<Map<String, Object>> alerts = (List<Map<String, Object>>) s.get("alerts");
            p.heading("Worth a look (" + alerts.size() + ")");
            if (alerts.isEmpty()) {
                p.para(PLAIN, 10, "Nothing worrying today.");
            }
            for (Map<String, Object> a : alerts) {
                p.para(PLAIN, 10, "- " + a.get("summary") + (a.get("userName") != null ? " (" + a.get("userName") + ")" : ""));
            }

            List<Map<String, Object>> top = (List<Map<String, Object>>) s.get("topDishes");
            if (!top.isEmpty()) {
                p.heading("Best sellers");
                for (Map<String, Object> t : top) {
                    p.row(String.valueOf(t.get("name")), n(t.get("qty")).stripTrailingZeros().toPlainString() + " sold, " + kes(t.get("sales")));
                }
            }
            Map<String, Object> menu = (Map<String, Object>) s.get("menuReport");
            if (menu != null && !((List<?>) menu.get("dishes")).isEmpty()) {
                p.heading("Your menu this past week");
                for (String[] g : new String[][]{{"star", "Keep"}, {"promote", "Promote"}, {"fix_price", "Raise the price a little"}, {"drop", "Think about dropping"}}) {
                    List<Object> names = (List<Object>) menu.get(g[0]);
                    if (names != null && !names.isEmpty()) {
                        p.para(PLAIN, 10, g[1] + ": " + String.join(", ", names.stream().map(String::valueOf).toList()));
                    }
                }
            }
            p.y -= 10;
            p.need(14);
            p.text(PLAIN, 8, "Made by BizNez for " + s.get("business") + ".", MARGIN);
            p.out.close();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            p.doc.save(bytes);
            p.doc.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't make the PDF", e);
        }
    }
}
