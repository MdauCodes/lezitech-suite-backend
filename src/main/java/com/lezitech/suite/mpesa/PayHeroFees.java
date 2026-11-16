package com.lezitech.suite.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What PayHero charges a business for each M-Pesa payment, by amount band. PayHero publishes the table at a public
 * address (no account needed); we read it once a day and keep a copy in the app for when PayHero can't be reached.
 * The charge comes out of the business's PayHero service wallet, not out of the customer's payment.
 */
@Component
public class PayHeroFees {

    public static final String URL = "https://backend.payhero.co.ke/api/transaction_fees";
    private static final Logger log = LoggerFactory.getLogger(PayHeroFees.class);

    public record Band(BigDecimal from, BigDecimal to, BigDecimal price) {}

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private volatile List<Band> bands;
    private volatile String source = "copy kept in BizNez";
    private volatile Instant fetchedAt;

    public PayHeroFees(ObjectMapper json) {
        this.json = json;
        try (InputStream in = new ClassPathResource("payhero-fees.json").getInputStream()) {
            this.bands = parse(json.readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException("payhero-fees.json is missing or broken", e);
        }
    }

    static List<Band> parse(JsonNode arr) {
        List<Band> out = new ArrayList<>();
        for (JsonNode n : arr) {
            BigDecimal from = n.path("amount_from").decimalValue(), to = n.path("amount_to").decimalValue();
            if (to.compareTo(from) < 0) {
                continue; // PayHero's table has a stray empty band (35,000 to 34,999); skip anything like it
            }
            out.add(new Band(from, to, n.path("price").decimalValue()));
        }
        out.sort((a, b) -> a.from().compareTo(b.from()));
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no fee bands");
        }
        return List.copyOf(out);
    }

    /** Read PayHero's current table: at start-up (after a minute) and then once a day. Keeps the last good one on failure. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 86_400_000)
    public void refresh() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + r.statusCode());
            }
            bands = parse(json.readTree(r.body()));
            source = "PayHero, read today";
            fetchedAt = Instant.now();
        } catch (Exception e) {
            log.info("PayHero fee table not refreshed, keeping the last one: {}", e.getMessage());
        }
    }

    /** PayHero's charge for one payment of this amount (whole shillings, as the prompt is sent). Above the top band: the top price. */
    public BigDecimal fee(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal a = amount.setScale(0, java.math.RoundingMode.CEILING);
        List<Band> b = bands;
        for (Band band : b) {
            if (a.compareTo(band.from()) >= 0 && a.compareTo(band.to()) <= 0) {
                return band.price();
            }
        }
        return a.compareTo(b.get(0).from()) < 0 ? BigDecimal.ZERO : b.get(b.size() - 1).price();
    }

    public Map<String, Object> table() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bands", bands.stream().map(x -> Map.of("from", x.from(), "to", x.to(), "price", x.price())).toList());
        out.put("source", source);
        out.put("fetchedAt", fetchedAt == null ? null : fetchedAt.toString());
        return out;
    }
}
