package com.lezitech.suite.files;

import com.lezitech.suite.common.ApiException;

import java.io.ByteArrayOutputStream;

/**
 * Takes the hidden details (camera, location, time, software notes) out of a photo without changing the picture.
 * JPEG: drops the APPn and comment blocks. PNG: drops text and EXIF chunks. WebP: drops the EXIF and XMP chunks.
 * A file that is not built the way its type says is refused.
 */
final class ImageScrub {

    private ImageScrub() {
    }

    static byte[] strip(byte[] b, String type) {
        try {
            return switch (type) {
                case "image/jpeg" -> jpeg(b);
                case "image/png" -> png(b);
                case "image/webp" -> webp(b);
                default -> b;
            };
        } catch (IndexOutOfBoundsException e) {
            throw broken();
        }
    }

    private static ApiException broken() {
        return ApiException.bad("That photo can't be read. Take it again");
    }

    private static byte[] jpeg(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i < b.length) {
            if ((b[i] & 0xff) != 0xFF) {
                throw broken();
            }
            int marker = b[i + 1] & 0xff;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD9) { // end of image
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            if (marker == 0xDA) { // start of scan: the picture data runs to the end
                out.write(b, i, b.length - i);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // markers with no length
                out.write(b, i, 2);
                i += 2;
                continue;
            }
            int len = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (len < 2 || i + 2 + len > b.length) {
                throw broken();
            }
            boolean hidden = (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
            if (!hidden) {
                out.write(b, i, 2 + len);
            }
            i += 2 + len;
        }
        throw broken(); // no picture data
    }

    private static byte[] png(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int i = 8;
        boolean data = false;
        while (i + 12 <= b.length) {
            long len = ((long) (b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (len < 0 || i + 12 + len > b.length) {
                throw broken();
            }
            String name = new String(b, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int total = (int) len + 12;
            if (name.equals("IDAT")) {
                data = true;
            }
            if (!(name.equals("eXIf") || name.equals("tEXt") || name.equals("iTXt") || name.equals("zTXt") || name.equals("tIME"))) {
                out.write(b, i, total);
            }
            i += total;
            if (name.equals("IEND")) {
                break;
            }
        }
        if (!data) {
            throw broken();
        }
        return out.toByteArray();
    }

    private static byte[] webp(byte[] b) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        int i = 12;
        boolean picture = false;
        while (i + 8 <= b.length) {
            String name = new String(b, i, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long len = (b[i + 4] & 0xffL) | ((b[i + 5] & 0xffL) << 8) | ((b[i + 6] & 0xffL) << 16) | ((b[i + 7] & 0xffL) << 24);
            long padded = len + (len & 1);
            if (i + 8 + len > b.length) {
                throw broken();
            }
            int end = (int) Math.min(b.length, i + 8 + padded);
            if (name.equals("VP8 ") || name.equals("VP8L") || name.equals("ANMF")) {
                picture = true;
            }
            if (!(name.equals("EXIF") || name.equals("XMP "))) {
                byte[] chunk = java.util.Arrays.copyOfRange(b, i, end);
                if (name.equals("VP8X") && chunk.length > 8) {
                    chunk[8] = (byte) (chunk[8] & ~(0x08 | 0x04)); // no EXIF, no XMP
                }
                body.write(chunk, 0, chunk.length);
            }
            i = end;
        }
        if (!picture) {
            throw broken();
        }
        byte[] inner = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(inner.length + 12);
        long size = inner.length + 4;
        out.write(new byte[]{'R', 'I', 'F', 'F', (byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24), 'W', 'E', 'B', 'P'}, 0, 12);
        out.write(inner, 0, inner.length);
        return out.toByteArray();
    }
}
