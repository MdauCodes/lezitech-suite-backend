package com.lezitech.suite.carcass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Whether this business has the carcass switch on: the owner's saved choice, else what its sub-sector has. */
@Component
public class CarcassFeature {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SectorCatalog sectors;

    public CarcassFeature(JdbcTemplate jdbc, ObjectMapper json, SectorCatalog sectors) {
        this.jdbc = jdbc;
        this.json = json;
        this.sectors = sectors;
    }

    public boolean enabled() {
        Map<String, Object> t = jdbc.queryForMap("SELECT sector, sub_sector, settings::text AS s FROM tenants WHERE id = ?", TenantContext.tenantId());
        try {
            JsonNode v = json.readTree((String) t.get("s")).path("features").path("carcass");
            if (v.isBoolean()) {
                return v.asBoolean();
            }
        } catch (Exception ignored) {
            // fall back to the sub-sector default
        }
        return sectors.get((String) t.get("sector"), (String) t.get("sub_sector")).path("features").path("carcass").asBoolean(false);
    }

    public void require() {
        if (!enabled()) {
            throw ApiException.forbidden("Carcasses are switched off for this business. The owner can switch them on in Settings.");
        }
    }
}
