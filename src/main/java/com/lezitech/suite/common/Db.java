package com.lezitech.suite.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.tenant.TenantContext;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin SQL helper. Rows come back as JSON-ready maps with camelCase keys.
 * Parameters are name/value pairs; :t (tenant) and :u (user) are filled in from the caller.
 */
@Component
public class Db {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public Db(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Map<String, Object>> list(String sql, Object... kv) {
        return jdbc.query(sql, params(kv), (rs, i) -> row(rs));
    }

    public Optional<Map<String, Object>> find(String sql, Object... kv) {
        List<Map<String, Object>> rows = list(sql, kv);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<String, Object> one(String what, String sql, Object... kv) {
        return find(sql, kv).orElseThrow(() -> ApiException.notFound(what));
    }

    public int exec(String sql, Object... kv) {
        return jdbc.update(sql, params(kv));
    }

    public <T> T value(String sql, Class<T> type, Object... kv) {
        List<T> rows = jdbc.queryForList(sql, params(kv), type);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** INSERT ... RETURNING id */
    public UUID insert(String sql, Object... kv) {
        return jdbc.queryForObject(sql, params(kv), UUID.class);
    }

    public String toJson(Object value) {
        try {
            return json.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw ApiException.bad("Invalid JSON value");
        }
    }

    private MapSqlParameterSource params(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalStateException("Parameters must be name/value pairs");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        TenantContext.Caller caller = TenantContext.callerOrNull();
        if (caller != null) {
            p.addValue("t", caller.tenantId());
            p.addValue("u", caller.userId());
        }
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof String s && UUID_TEXT.matcher(s).matches()) {
                v = UUID.fromString(s);
            }
            p.addValue((String) kv[i], v);
        }
        return p;
    }

    /** Ids come back from rows and JSON bodies as strings; bind them as uuid. */
    private static final java.util.regex.Pattern UUID_TEXT =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int c = 1; c <= md.getColumnCount(); c++) {
            out.put(camel(md.getColumnLabel(c)), convert(rs.getObject(c)));
        }
        return out;
    }

    private Object convert(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof PGobject pg && pg.getType() != null && pg.getType().startsWith("json")) {
            try {
                return json.readTree(pg.getValue());
            } catch (Exception e) {
                return pg.getValue();
            }
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof UUID u) {
            return u.toString();
        }
        if (v instanceof Array a) {
            Object[] arr = (Object[]) a.getArray();
            List<Object> list = new ArrayList<>(Arrays.asList(arr));
            list.replaceAll(o -> o instanceof UUID u ? u.toString() : o);
            return list;
        }
        return v;
    }

    private static String camel(String snake) {
        StringBuilder sb = new StringBuilder(snake.length());
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
