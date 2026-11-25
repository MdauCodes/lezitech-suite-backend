package com.lezitech.suite.tenant;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Pins every transaction to the caller's tenant: app.tenant is set transaction-locally
 * on the pooled connection, so RLS policies see it and it can't leak to the next borrower.
 */
public class TenantTransactionManager extends DataSourceTransactionManager {

    public TenantTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition) throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        TenantContext.Caller caller = TenantContext.callerOrNull();
        try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.tenant', ?, true)")) {
            ps.setString(1, caller == null ? "" : caller.tenantId().toString());
            ps.execute();
        }
        // LeziTech support looking at a business with no grant: the database itself refuses every write.
        if (com.lezitech.suite.auth.SupportAccess.lookOnly()) {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET TRANSACTION READ ONLY");
            }
        }
    }
}
