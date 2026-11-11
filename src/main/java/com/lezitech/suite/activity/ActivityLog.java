package com.lezitech.suite.activity;

import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The business's own record of what staff did that touches money or stock: price changes, sales below the lowest
 * price, discounts, cancelled bills, refunds, item price edits, stock count differences, cash short or over, and
 * changes to who can do what. Written in the same transaction as the action, so it can't be skipped.
 */
@Component
public class ActivityLog {

    public static final String INFO = "INFO";
    public static final String NOTICE = "NOTICE";
    public static final String CONCERN = "CONCERN";

    private final Db db;

    public ActivityLog(Db db) {
        this.db = db;
    }

    public void record(String kind, String severity, String summary, UUID orderId, UUID itemId, BigDecimal amount, String approvedBy,
                       Map<String, Object> detail) {
        TenantContext.Caller c = TenantContext.callerOrNull();
        if (c == null) {
            return;
        }
        db.exec("""
                INSERT INTO activity_log (tenant_id, user_id, user_name, approved_by, kind, severity, summary, order_id, item_id, amount, detail)
                VALUES (:t, :user, :name, :approver, :kind, :sev, :summary, :order, :item, :amount, cast(:detail as jsonb))""",
                "user", c.userId(), "name", c.name(), "approver", approvedBy, "kind", kind, "sev", severity, "summary", summary,
                "order", orderId, "item", itemId, "amount", amount == null ? null : amount.setScale(2, java.math.RoundingMode.HALF_UP),
                "detail", db.toJson(detail == null ? Map.of() : detail));
    }

    public void record(String kind, String severity, String summary) {
        record(kind, severity, summary, null, null, null, null, null);
    }
}
