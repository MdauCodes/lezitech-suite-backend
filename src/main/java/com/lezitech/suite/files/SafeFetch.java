package com.lezitech.suite.files;

import com.lezitech.suite.common.ApiException;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reading a web address a business gave us (an Airbnb or Booking.com calendar link), without letting it reach this
 * server or its private network:
 * <ul>
 *   <li>https only: a private machine can't present a valid certificate for an outside name, which also defeats
 *       DNS tricks that point a public name at a private address after we checked it;</li>
 *   <li>every address the name resolves to must be public (not loopback, private, link-local, carrier-grade NAT,
 *       0.0.0.0/8, multicast, unique-local IPv6 or IPv4 dressed up as IPv6);</li>
 *   <li>no redirects, a size limit, short timeouts, and only text answers.</li>
 * </ul>
 */
public final class SafeFetch {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private SafeFetch() {
    }

    /** Throws ApiException (400) with a plain reason if the address isn't allowed. */
    public static URI check(String link) {
        URI uri;
        try {
            uri = URI.create(link == null ? "" : link.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("That isn't a web address");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw ApiException.bad("Use the https:// calendar link the other site gives you");
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.bad("That address isn't allowed");
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(a)) {
                    throw ApiException.bad("That address points to a private network");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw ApiException.bad("That address can't be found");
        }
        return uri;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first != 0 && !(first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                    && !(first == 169 && second == 254) && first < 224;
        }
        if (a instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // unique local fc00::/7
            }
            // IPv4-mapped (::ffff:a.b.c.d) or IPv4-compatible: judge the IPv4 inside.
            boolean zeros = true;
            for (int i = 0; i < 10; i++) {
                zeros &= b[i] == 0;
            }
            if (zeros && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The text at an allowed https address, at most {@code maxBytes}. */
    public static String text(String link, int maxBytes) {
        URI uri = check(link);
        try {
            HttpResponse<InputStream> res = CLIENT.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Busynez calendar sync").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() / 100 == 3) {
                    throw ApiException.bad("That link moved to another address. Copy the calendar link again from the other site");
                }
                if (res.statusCode() != 200) {
                    throw ApiException.bad("The other site answered " + res.statusCode() + ". Check the calendar link");
                }
                String type = res.headers().firstValue("Content-Type").orElse("text/calendar").toLowerCase();
                if (!type.startsWith("text/") && !type.contains("calendar")) {
                    throw ApiException.bad("That link isn't a calendar");
                }
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw ApiException.bad("That calendar is too big");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw ApiException.bad("Couldn't reach the other site: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.bad("Stopped while reading the calendar");
        }
    }
}
