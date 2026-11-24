package com.lezitech.suite.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The first-touch source of a new business, worked out from the cleaned acquisition the sign-up form sends. */
public final class SignupSource {

    private SignupSource() {
    }

    /** Null when nothing was sent (the business is then shown as "not known"). */
    public static ObjectNode of(ObjectMapper json, JsonNode acq) {
        if (acq == null) {
            return null;
        }
        var s = VisitSource.classify(text(acq, "referrer"), text(acq, "utm_source"), text(acq, "utm_medium"), acq.hasNonNull("gclid"), acq.hasNonNull("fbclid"));
        ObjectNode out = json.createObjectNode();
        out.put("source", s.key());
        if (s.detail() != null) {
            out.put("detail", s.detail());
        }
        String campaign = text(acq, "utm_campaign");
        if (campaign != null) {
            out.put("campaign", campaign.length() > 100 ? campaign.substring(0, 100) : campaign);
        }
        String landing = text(acq, "landing");
        if (landing != null) {
            out.put("landing", landing.length() > 200 ? landing.substring(0, 200) : landing);
        }
        return out;
    }

    private static String text(JsonNode n, String k) {
        return n.hasNonNull(k) && !n.get(k).asText().isBlank() ? n.get(k).asText() : null;
    }
}
