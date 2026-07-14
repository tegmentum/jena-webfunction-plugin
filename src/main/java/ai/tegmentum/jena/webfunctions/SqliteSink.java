package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite backend for the v0.5 sink imports. Ports {@code SqliteSink} from
 * {@code oxigraph-wf/src/sink.rs} to Jena/JDBC.
 *
 * <p>URL scheme (mirroring the Rust plugin):
 * <ul>
 *   <li>{@code sqlite:///data/mv.db} → opens the file {@code /data/mv.db}
 *   <li>{@code sqlite://memory} or {@code sqlite:///:memory:} → anonymous
 *       in-memory database (used by tests)
 * </ul>
 */
final class SqliteSink implements Sink {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private final Connection conn;

    private SqliteSink(final Connection conn) {
        this.conn = conn;
    }

    static SqliteSink open(final URI url) throws SQLException {
        // Path semantics — mirrors the Rust plugin exactly:
        //   sqlite:///data/mv.db → path is "/data/mv.db" (file)
        //   sqlite:///:memory:   → path is "/:memory:" (in-memory)
        //   sqlite://memory      → authority is "memory", path is "" (in-memory)
        //   sqlite://:memory:    → authority is ":memory:", path is "" (in-memory)
        final String path = url.getPath() == null ? "" : url.getPath();
        final String authority = url.getAuthority() == null ? "" : url.getAuthority();
        final String jdbcUrl;
        if (path.isEmpty() || path.equals("/:memory:") || path.equals("/")
                || "memory".equalsIgnoreCase(authority)
                || ":memory:".equalsIgnoreCase(authority)) {
            jdbcUrl = "jdbc:sqlite::memory:";
        } else {
            jdbcUrl = "jdbc:sqlite:" + path;
        }
        final Connection c = DriverManager.getConnection(jdbcUrl);
        return new SqliteSink(c);
    }

    @Override
    public ComponentVal execute(final String query, final List<ComponentVal> params) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            for (int i = 0; i < params.size(); i++) {
                bindParam(stmt, i + 1, params.get(i));
            }
            final boolean hasResultSet = stmt.execute();
            if (!hasResultSet) {
                return emptyBindingSets();
            }
            try (ResultSet rs = stmt.getResultSet()) {
                return encodeResultSet(rs);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    // ---- WIT → SQLite param binding ---------------------------------------

    private static void bindParam(final PreparedStatement stmt, final int index,
                                  final ComponentVal v) throws SQLException {
        final ComponentVariant variant = v.asVariant();
        final String caseName = variant.getCaseName();
        final ComponentVal payload = variant.getPayload().orElse(null);
        switch (caseName) {
            case "iri": {
                final String s = payload == null ? "" : payload.asString();
                stmt.setString(index, s);
                return;
            }
            case "bnode": {
                final String s = payload == null ? "" : payload.asString();
                stmt.setString(index, "_:" + s);
                return;
            }
            case "literal": {
                if (payload == null) {
                    throw new IllegalStateException("wf: literal variant has no payload");
                }
                final Map<String, ComponentVal> fields = payload.asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                bindLiteral(stmt, index, label, datatype);
                return;
            }
            default:
                throw new IllegalStateException("wf sink: unknown value variant case: " + caseName);
        }
    }

    /**
     * Coerce common xsd datatypes to their native SQLite scalar. Everything
     * else falls through to TEXT. Mirrors {@code val_to_sqlite} in sink.rs.
     */
    private static void bindLiteral(final PreparedStatement stmt, final int index,
                                    final String label, final String datatype) throws SQLException {
        switch (datatype) {
            case XSD + "integer":
            case XSD + "int":
            case XSD + "long":
            case XSD + "short":
            case XSD + "byte":
                try {
                    stmt.setLong(index, Long.parseLong(label));
                } catch (NumberFormatException nfe) {
                    stmt.setString(index, label);
                }
                return;
            case XSD + "decimal":
            case XSD + "double":
            case XSD + "float":
                try {
                    stmt.setDouble(index, Double.parseDouble(label));
                } catch (NumberFormatException nfe) {
                    stmt.setString(index, label);
                }
                return;
            case XSD + "boolean":
                if ("true".equals(label) || "1".equals(label)) {
                    stmt.setInt(index, 1);
                } else if ("false".equals(label) || "0".equals(label)) {
                    stmt.setInt(index, 0);
                } else {
                    stmt.setString(index, label);
                }
                return;
            default:
                stmt.setString(index, label);
        }
    }

    // ---- SQLite → WIT binding-sets ----------------------------------------

    private static ComponentVal encodeResultSet(final ResultSet rs) throws SQLException {
        final ResultSetMetaData md = rs.getMetaData();
        final int colCount = md.getColumnCount();
        final List<String> colNames = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            final String name = md.getColumnLabel(i);
            colNames.add(name == null ? "?column?" : name);
        }

        final List<ComponentVal> varsVals = new ArrayList<>(colCount);
        for (String name : colNames) varsVals.add(ComponentVal.string(name));

        final List<ComponentVal> rowVals = new ArrayList<>();
        while (rs.next()) {
            final List<ComponentVal> bindingVals = new ArrayList<>(colCount);
            for (int i = 0; i < colCount; i++) {
                final ComponentVal cell = sqlColumnToVal(rs, i + 1, md.getColumnType(i + 1));
                // Null bindings are omitted — WIT binding-set semantics treat
                // absent bindings as UNDEF (matches the Rust sink behaviour).
                if (cell == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(colNames.get(i)));
                bindingFields.put("value", cell);
                bindingVals.add(ComponentVal.record(bindingFields));
            }
            rowVals.add(ComponentVal.list(bindingVals));
        }

        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(varsVals));
        bs.put("rows", ComponentVal.list(rowVals));
        return ComponentVal.record(bs);
    }

    /**
     * Convert a SQL column into a WIT {@code value} variant. INTEGER →
     * xsd:integer, REAL/NUMERIC → xsd:decimal, TEXT → xsd:string,
     * BLOB → xsd:base64Binary. Nulls are returned as {@code null} so the
     * caller can skip them (WIT UNDEF semantics).
     *
     * <p>Mirrors {@code sqlite_to_val} in sink.rs — we don't know the original
     * xsd datatype on the way back, so the reverse mapping is looser. Guests
     * that need stricter typing project their own conversions on top.
     */
    private static ComponentVal sqlColumnToVal(final ResultSet rs, final int idx,
                                                final int sqlType) throws SQLException {
        switch (sqlType) {
            case java.sql.Types.NULL:
                return null;
            case java.sql.Types.INTEGER:
            case java.sql.Types.BIGINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.TINYINT: {
                final long v = rs.getLong(idx);
                if (rs.wasNull()) return null;
                return literalVal(Long.toString(v), XSD + "integer");
            }
            case java.sql.Types.REAL:
            case java.sql.Types.FLOAT:
            case java.sql.Types.DOUBLE:
            case java.sql.Types.DECIMAL:
            case java.sql.Types.NUMERIC: {
                final double v = rs.getDouble(idx);
                if (rs.wasNull()) return null;
                return literalVal(Double.toString(v), XSD + "decimal");
            }
            case java.sql.Types.BOOLEAN:
            case java.sql.Types.BIT: {
                final boolean b = rs.getBoolean(idx);
                if (rs.wasNull()) return null;
                return literalVal(b ? "true" : "false", XSD + "boolean");
            }
            case java.sql.Types.BLOB:
            case java.sql.Types.VARBINARY:
            case java.sql.Types.BINARY:
            case java.sql.Types.LONGVARBINARY: {
                final byte[] b = rs.getBytes(idx);
                if (b == null || rs.wasNull()) return null;
                return literalVal(java.util.Base64.getEncoder().encodeToString(b),
                                  XSD + "base64Binary");
            }
            default: {
                final String s = rs.getString(idx);
                if (s == null || rs.wasNull()) return null;
                return literalVal(s, XSD + "string");
            }
        }
    }

    private static ComponentVal literalVal(final String label, final String datatype) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("label", ComponentVal.string(label));
        fields.put("datatype", ComponentVal.string(datatype));
        fields.put("lang", ComponentVal.none());
        return ComponentVal.variant("literal", ComponentVal.record(fields));
    }

    private static ComponentVal emptyBindingSets() {
        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(new ArrayList<>()));
        bs.put("rows", ComponentVal.list(new ArrayList<>()));
        return ComponentVal.record(bs);
    }

    // Silence unused-import warnings for Optional in case a future change
    // needs it inside literal decoding.
    @SuppressWarnings("unused")
    private static void _touch(Optional<?> o) {}
}
