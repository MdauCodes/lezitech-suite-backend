package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;

import java.math.BigDecimal;
import java.util.Map;

/** Items sold by weight (meat, by the kilo): the line quantity is kilos, up to 3 decimals, and the line total is whole shillings. */
final class ByWeight {

    private static final BigDecimal MAX_KG = BigDecimal.valueOf(1000);

    private ByWeight() {
    }

    static boolean is(Map<String, Object> item) {
        return Boolean.TRUE.equals(item.get("soldByWeight"));
    }

    static BigDecimal checkedKg(BigDecimal kg, String name) {
        if (kg.stripTrailingZeros().scale() > 3) {
            throw ApiException.bad("Weigh " + name + " to 3 decimals at most, for example 0.750 kg");
        }
        if (kg.compareTo(MAX_KG) > 0) {
            throw ApiException.bad("That is too much " + name + " for one line");
        }
        return kg;
    }
}
