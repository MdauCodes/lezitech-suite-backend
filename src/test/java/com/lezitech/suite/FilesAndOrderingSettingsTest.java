package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.support.IntegrationTest;
import com.lezitech.suite.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Package A: uploaded files stay with their business, only menu photos are public, and the ordering settings. */
class FilesAndOrderingSettingsTest extends IntegrationTest {

    @Autowired
    FileService files;

    /** The smallest thing that starts like a JPEG. */
    static byte[] jpeg(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 7);
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    @Test
    void aMenuPhotoIsKeptForItsBusinessAndShownOnTheMenu() {
        JsonNode owner = signup("restaurant");
        String token = token(owner);
        String slug = owner.at("/tenant/slug").asText();
        String item = item(token, "").get("id").asText();

        Res up = upload("/items/" + item + "/photo", token, jpeg(2048), "chips.jpg");
        assertThat(up.status()).as(up.body().toString()).isEqualTo(200);
        String file = up.at("/photoFileId").asText();

        Raw mine = raw("/files/" + file, token);
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.contentType()).startsWith("image/jpeg");
        assertThat(mine.bytes()).isEqualTo(jpeg(2048));
        assertThat(raw("/public/files/" + slug + "/" + file, null).status()).isEqualTo(200);

        // Another business can't see it, by id or through its own public address.
        JsonNode other = signup("restaurant");
        assertThat(raw("/files/" + file, token(other)).status()).isEqualTo(404);
        assertThat(raw("/public/files/" + other.at("/tenant/slug").asText() + "/" + file, null).status()).isEqualTo(404);

        // Replacing the photo removes the old file.
        String second = upload("/items/" + item + "/photo", token, jpeg(1024), "chips2.jpg").at("/photoFileId").asText();
        assertThat(raw("/files/" + file, token).status()).isEqualTo(404);
        assertThat(raw("/files/" + second, token).status()).isEqualTo(200);
    }

    @Test
    void onlyRealPhotosOfASensibleSizeAreAccepted() {
        String token = token(signup("restaurant"));
        String item = item(token, "").get("id").asText();
        assertThat(upload("/items/" + item + "/photo", token, "not a photo".getBytes(), "x.jpg").status()).isEqualTo(400);
        assertThat(upload("/items/" + item + "/photo", token, jpeg(3 * 1024 * 1024), "big.jpg").status()).isEqualTo(400);
        // A cashier can't change the menu.
        assertThat(upload("/items/" + item + "/photo", staff(token, "CASHIER"), jpeg(1024), "c.jpg").status()).isEqualTo(403);
    }

    @Test
    void anEtrIsNeverPublic() {
        JsonNode owner = signup("restaurant");
        UUID tenant = UUID.fromString(owner.at("/tenant/id").asText());
        UUID user = UUID.fromString(owner.at("/user/id").asText());
        UUID etr = TenantContext.runAs(tenant, user, () -> files.save(FileService.Purpose.ETR, "%PDF-1.4 receipt".getBytes()));
        assertThat(raw("/files/" + etr, token(owner)).status()).isEqualTo(200);
        assertThat(raw("/public/files/" + owner.at("/tenant/slug").asText() + "/" + etr, null).status()).isEqualTo(404);
    }

    @Test
    void orderingSettingsHaveSensibleDefaultsAndAreChecked() {
        String token = token(signup("restaurant"));
        JsonNode d = get("/settings/ordering", token).body();
        assertThat(d.get("selfOrder").asBoolean()).isTrue();
        assertThat(d.get("payWhen").asText()).isEqualTo("READY");
        assertThat(d.get("tipPresets").toString()).isEqualTo("[300,500,1000,3000]");
        assertThat(d.at("/tipShares/kitchen").asInt()).isEqualTo(45);

        assertThat(call("PUT", "/settings", token, Map.of("settings", Map.of("ordering", Map.of("payWhen", "FIRST")))).status()).isEqualTo(200);
        assertThat(call("PUT", "/settings", token, Map.of("settings", Map.of("ordering",
                Map.of("tipShares", Map.of("kitchen", 50, "business", 0, "server", 50))))).status()).isEqualTo(200);
        JsonNode after = get("/settings/ordering", token).body();
        assertThat(after.get("payWhen").asText()).as("earlier choice kept").isEqualTo("FIRST");
        assertThat(after.at("/tipShares/server").asInt()).isEqualTo(50);

        assertThat(call("PUT", "/settings", token, Map.of("settings", Map.of("ordering",
                Map.of("tipShares", Map.of("kitchen", 50, "business", 10, "server", 50))))).status()).isEqualTo(400);
        assertThat(call("PUT", "/settings", token, Map.of("settings", Map.of("ordering", Map.of("payWhen", "LATER")))).status()).isEqualTo(400);
        assertThat(call("PUT", "/settings", token, Map.of("settings", Map.of("ordering", Map.of("tipPresets", List.of(0))))).status()).isEqualTo(400);

        // A shop doesn't take table orders unless the owner switches it on.
        assertThat(get("/settings/ordering", token(signup("retail"))).body().get("selfOrder").asBoolean()).isFalse();
    }

    @Test
    void loyaltyRulesAreChecked() {
        String token = token(signup("restaurant"));
        assertThat(call("PUT", "/settings", token, Map.of("settings", Map.of("loyalty",
                Map.of("on", true, "pointsPer100", 1, "pointValue", 1, "minToRedeem", 100, "expiryMonths", 12)))).status()).isEqualTo(200);
        assertThat(call("PUT", "/settings", token, Map.of("settings", Map.of("loyalty", Map.of("on", true, "pointsPer100", 0)))).status()).isEqualTo(400);
    }
}
