package com.lezitech.suite.files;

import com.lezitech.suite.common.ApiException;

/**
 * Photos from web addresses (a spreadsheet's "Photo link" column). Kept outside any database transaction, so a bad
 * link is just a note in the import's result and never undoes the rest of it.
 */
public final class PhotoLinks {

    private PhotoLinks() {
    }

    private static final java.net.http.HttpClient LINKS = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();

    /**
     * A photo from a web address (a spreadsheet's "Photo link" column): public http(s) only, never this server's own
     * network, at most 8 MB fetched; large photos are made smaller (JPEG, 1024 px) so they fit the photo limit.
     */
    public static byte[] fetch(String link, int maxBytes) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(link.trim());
        } catch (RuntimeException e) {
            throw ApiException.bad("That photo link isn't a web address");
        }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw ApiException.bad("Photo links must start with https://");
        }
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(uri.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()
                        || (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc)) {
                    throw ApiException.bad("That photo link points to a private address");
                }
            }
            java.net.http.HttpResponse<java.io.InputStream> res = LINKS.send(java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofSeconds(15)).header("User-Agent", "BizNez photo import").GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                throw ApiException.bad("The photo link didn't open (" + res.statusCode() + ")");
            }
            byte[] bytes;
            try (java.io.InputStream in = res.body()) {
                bytes = in.readNBytes(8 * 1024 * 1024 + 1);
            }
            if (bytes.length > 8 * 1024 * 1024) {
                throw ApiException.bad("The photo at that link is bigger than 8 MB");
            }
            if (!isPhoto(bytes)) {
                throw ApiException.bad("That link isn't a photo (JPG, PNG or WebP)");
            }
            byte[] out = bytes.length > maxBytes ? smaller(bytes) : bytes;
            if (out.length > maxBytes) {
                throw ApiException.bad("The photo at that link is too big to use");
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Couldn't fetch the photo from that link");
        }
    }

    private static boolean isPhoto(byte[] b) {
        boolean jpg = b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
        boolean png = b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean webp = b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return jpg || png || webp;
    }

    /** A big JPEG or PNG made 1024 px on its longest side, as a JPEG. */
    private static byte[] smaller(byte[] bytes) throws java.io.IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (img == null) {
            throw ApiException.bad("The photo at that link is too big to use");
        }
        double scale = Math.min(1.0, 1024.0 / Math.max(img.getWidth(), img.getHeight()));
        int w = (int) Math.round(img.getWidth() * scale), h = (int) Math.round(img.getHeight() * scale);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "jpg", o);
        return o.toByteArray();
    }

}
