package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tour operators' departures and seats, and quotations that become invoices (wholesale and services). */
class TripsAndQuotesTest extends IntegrationTest {

    @Test
    void seatsCantBeOversoldAndADepositGoesOnTheBooking() {
        String owner = token(signup("tours"));
        JsonNode pkg = item(owner, "");
        Res trip = post("/trips", owner, Map.of("itemId", pkg.get("id").asText(), "startsOn", LocalDate.now().plusDays(21).toString(),
                "seats", 4, "vehicle", "Land Cruiser KDA 123A", "guide", "Joseph"));
        assertThat(trip.status()).as(trip.body().toString()).isEqualTo(200);
        String id = trip.at("/id").asText();

        // A deposit can only be taken in a way we really handle: "POINTS" would add the money to the bill without
        // taking any points off the customer, and a made-up word would be money recorded as nothing.
        Res points = post("/trips/" + id + "/bookings", owner, Map.of("leadName", "The Smiths", "pax", 3,
                "deposit", 10000, "depositMethod", "POINTS"));
        assertThat(points.status()).isEqualTo(400);
        assertThat(points.at("/message").asText()).contains("cash, M-Pesa, card or bank");

        Res first = post("/trips/" + id + "/bookings", owner, Map.of("leadName", "The Smiths", "pax", 3, "nationality", "UK",
                "deposit", 10000, "depositMethod", "MPESA", "depositReference", "TKL1"));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        // Only one seat left.
        Res tooMany = post("/trips/" + id + "/bookings", owner, Map.of("leadName", "The Otienos", "pax", 2));
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.at("/message").asText()).contains("1 seat");
        assertThat(post("/trips/" + id + "/bookings", owner, Map.of("leadName", "Solo traveller", "pax", 1)).status()).isEqualTo(200);

        JsonNode t = get("/trips/" + id, owner).body();
        assertThat(t.toString()).contains("The Smiths").contains("UK");
        // Full departures can't take more.
        assertThat(post("/trips/" + id + "/bookings", owner, Map.of("leadName", "Late", "pax", 1)).status()).isEqualTo(409);
        // Another business can't see the departure.
        assertThat(get("/trips/" + id, token(signup("tours"))).status()).isEqualTo(404);
    }

    @Test
    void aQuoteIsntASaleUntilItsConverted() {
        String owner = token(signup("wholesale"));
        String product = item(owner, "").get("id").asText();
        Res quote = post("/orders", owner, Map.of("kind", "QUOTE", "lines", line(product, 10)));
        assertThat(quote.status()).isEqualTo(200);
        String id = quote.at("/id").asText();
        double total = quote.at("/total").asDouble();

        // A quotation can't be paid; converting it makes the invoice, which can.
        assertThat(post("/orders/" + id + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", total)))).status()).isEqualTo(400);
        JsonNode invoice = post("/orders/" + id + "/convert", owner, Map.of()).body();
        assertThat(invoice.get("kind").asText()).isEqualTo("SALE");
        Res paid = post("/orders/" + id + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", total))));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(paid.at("/status").asText()).isEqualTo("PAID");
        // A paid sale can't be converted again.
        assertThat(post("/orders/" + id + "/convert", owner, Map.of()).status()).isEqualTo(409);
    }
}
