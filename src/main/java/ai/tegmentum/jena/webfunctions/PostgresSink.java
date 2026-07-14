package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * v0.5 {@link Sink} backed by PostgreSQL via the {@code org.postgresql:postgresql}
 * JDBC driver. Ports {@code PostgresSink} from {@code oxigraph-wf/src/sink.rs}
 * to Jena/JDBC.
 *
 * <p>URL scheme: standard libpq form —
 * {@code postgres://user:password@host:port/database}. The
 * {@code postgresql://} scheme is accepted as an alias (matches psql's
 * output). The URL is rewritten to the JDBC form
 * ({@code jdbc:postgresql://host:port/database}) with the user and
 * password lifted into JDBC connection properties so credentials embedded
 * in the sink URL don't have to round-trip through the driver's URL parser.
 *
 * <p>Placeholder syntax: Postgres speaks {@code $1}, {@code $2}, … natively.
 * Guests today (wf_fetch v0.5) emit {@code ?} placeholders because that's
 * what the SQLite backend expects. This sink hands the raw query text to
 * JDBC, which does the {@code ?} → {@code $N} translation itself
 * ({@code PreparedStatement} is portable across all JDBC drivers). No
 * host-side rewrite is needed on the Java side.
 *
 * <p>TLS: not enabled by default — the wf_relational v0.1 conformance
 * case brings up an {@code postgres:16-alpine} container on loopback.
 * Encrypted transport for production endpoints is out of scope until the
 * WIT surface grows a per-sink connection-options record. Callers that
 * want TLS can put {@code ?sslmode=require} on the URL — the driver will
 * honor it.
 */
public final class PostgresSink implements Sink {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private final Connection conn;

    private PostgresSink(final Connection conn) {
        this.conn = conn;
    }

    /**
     * Open a Postgres sink for the given URL. Errors propagate as
     * {@link SQLException} — the calling host closure wraps them into a
     * WIT {@code err} payload.
     */
    static PostgresSink open(final String rawUrl) throws SQLException {
        final ParsedPostgresUrl parsed = ParsedPostgresUrl.parse(rawUrl);
        final java.util.Properties props = new java.util.Properties();
        if (parsed.user != null) props.setProperty("user", parsed.user);
        if (parsed.password != null) props.setProperty("password", parsed.password);
        for (Map.Entry<String, String> e : parsed.queryParams.entrySet()) {
            props.setProperty(e.getKey(), e.getValue());
        }
        final Connection c = DriverManager.getConnection(parsed.jdbcUrl, props);
        return new PostgresSink(c);
    }

    @Override
    public ComponentVal execute(final String query, final List<ComponentVal> params) throws SQLException {
        // JDBC's PreparedStatement understands `?` positional placeholders
        // for every driver, including the Postgres one — the driver
        // rewrites them into `$N` on the wire. We do not need to do the
        // rewrite ourselves.
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            for (int i = 0; i < params.size(); i++) {
                bindParam(stmt, i + 1, params.get(i));
            }
            final boolean hasResultSet = stmt.execute();
            if (!hasResultSet) {
                return emptyBindingSets();
            }
            try (ResultSet rs = stmt.getResultSet()) {
                return encodeBindingSets(rs);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    // ---- WIT → JDBC param binding -----------------------------------------

    private static void bindParam(final PreparedStatement stmt, final int idx,
                                  final ComponentVal v) throws SQLException {
        final ComponentVariant variant = v.asVariant();
        final String caseName = variant.getCaseName();
        final Optional<ComponentVal> payload = variant.getPayload();
        switch (caseName) {
            case "iri": {
                stmt.setString(idx, payload.map(ComponentVal::asString).orElse(""));
                return;
            }
            case "bnode": {
                stmt.setString(idx, "_:" + payload.map(ComponentVal::asString).orElse(""));
                return;
            }
            case "literal": {
                final Map<String, ComponentVal> fields = payload
                        .orElseThrow(() -> new SQLException("literal variant missing payload"))
                        .asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                bindLiteralCoerced(stmt, idx, label, datatype);
                return;
            }
            default:
                throw new SQLException("wf sink: unknown value variant case: " + caseName);
        }
    }

    /**
     * Coerce common xsd datatypes to their native SQL scalar. Mirrors the
     * {@code val_to_postgres} table in the Rust reference — integer and
     * floating datatypes bind as their native Java scalar, boolean binds
     * as a bit, everything else falls through to TEXT.
     */
    private static void bindLiteralCoerced(final PreparedStatement stmt, final int idx,
                                           final String label, final String datatype) throws SQLException {
        switch (datatype) {
            case XSD + "integer":
            case XSD + "int":
            case XSD + "long":
            case XSD + "short":
            case XSD + "byte":
                try {
                    stmt.setLong(idx, Long.parseLong(label));
                } catch (NumberFormatException e) {
                    stmt.setString(idx, label);
                }
                return;
            case XSD + "decimal":
            case XSD + "double":
            case XSD + "float":
                try {
                    stmt.setDouble(idx, Double.parseDouble(label));
                } catch (NumberFormatException e) {
                    stmt.setString(idx, label);
                }
                return;
            case XSD + "boolean":
                if ("true".equals(label) || "1".equals(label)) {
                    stmt.setBoolean(idx, true);
                } else if ("false".equals(label) || "0".equals(label)) {
                    stmt.setBoolean(idx, false);
                } else {
                    stmt.setString(idx, label);
                }
                return;
            default:
                stmt.setString(idx, label);
        }
    }

    // ---- JDBC → WIT binding-sets ------------------------------------------

    /**
     * Encode a JDBC {@link ResultSet} as WIT {@code binding-sets}. NULL cells
     * are dropped so absent bindings carry WIT UNDEF semantics (matches
     * the SQLite path and the Rust sink).
     */
    private static ComponentVal encodeBindingSets(final ResultSet rs) throws SQLException {
        final ResultSetMetaData meta = rs.getMetaData();
        final int colCount = meta.getColumnCount();
        final List<String> colNames = new ArrayList<>(colCount);
        final List<Integer> colTypes = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            final String label = meta.getColumnLabel(i);
            colNames.add(label != null ? label : "?column?");
            colTypes.add(meta.getColumnType(i));
        }

        final List<ComponentVal> varsVals = new ArrayList<>(colCount);
        for (String n : colNames) varsVals.add(ComponentVal.string(n));

        final List<ComponentVal> rowVals = new ArrayList<>();
        while (rs.next()) {
            final List<ComponentVal> bindings = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                final ComponentVal cell = cellToWit(rs, i, colTypes.get(i - 1));
                if (cell == null) continue; // NULL → UNDEF
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(colNames.get(i - 1)));
                bindingFields.put("value", cell);
                bindings.add(ComponentVal.record(bindingFields));
            }
            rowVals.add(ComponentVal.list(bindings));
        }

        final Map<String, ComponentVal> bs = new LinkedHashMap<>();
        bs.put("vars", ComponentVal.list(varsVals));
        bs.put("rows", ComponentVal.list(rowVals));
        return ComponentVal.record(bs);
    }

    private static ComponentVal cellToWit(final ResultSet rs, final int col,
                                          final int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.NULL:
                return null;
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.SMALLINT:
            case Types.TINYINT: {
                final long v = rs.getLong(col);
                if (rs.wasNull()) return null;
                return literalVal(Long.toString(v), XSD + "integer");
            }
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.DECIMAL:
            case Types.NUMERIC: {
                final double v = rs.getDouble(col);
                if (rs.wasNull()) return null;
                return literalVal(Double.toString(v), XSD + "decimal");
            }
            case Types.BOOLEAN:
            case Types.BIT: {
                final boolean v = rs.getBoolean(col);
                if (rs.wasNull()) return null;
                return literalVal(v ? "true" : "false", XSD + "boolean");
            }
            case Types.VARBINARY:
            case Types.BINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB: {
                final byte[] b = rs.getBytes(col);
                if (b == null || rs.wasNull()) return null;
                return literalVal(java.util.Base64.getEncoder().encodeToString(b),
                        XSD + "base64Binary");
            }
            default: {
                final String s = rs.getString(col);
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

    // ---- URL parsing --------------------------------------------------------

    /**
     * Parsed form of the {@code postgres://[user[:password]@]host[:port]/db[?params]}
     * sink URL. Split out so we can hand JDBC a canonical form without
     * letting embedded credentials leak into the JDBC URL string (they
     * live in the Properties object instead).
     */
    static final class ParsedPostgresUrl {
        final String jdbcUrl;
        final String user;
        final String password;
        final Map<String, String> queryParams;

        private ParsedPostgresUrl(String jdbcUrl, String user, String password,
                                  Map<String, String> queryParams) {
            this.jdbcUrl = jdbcUrl;
            this.user = user;
            this.password = password;
            this.queryParams = queryParams;
        }

        static ParsedPostgresUrl parse(String raw) throws SQLException {
            String rest;
            if (raw.regionMatches(true, 0, "postgres://", 0, 11)) {
                rest = raw.substring(11);
            } else if (raw.regionMatches(true, 0, "postgresql://", 0, 13)) {
                rest = raw.substring(13);
            } else {
                throw new SQLException("postgres sink URL must start with postgres:// or postgresql:// — got " + raw);
            }
            // Split off ?query
            String query = "";
            int q = rest.indexOf('?');
            if (q >= 0) {
                query = rest.substring(q + 1);
                rest = rest.substring(0, q);
            }
            // Split off userinfo@authority[/db]
            String user = null;
            String password = null;
            int at = rest.indexOf('@');
            if (at >= 0) {
                final String userinfo = rest.substring(0, at);
                rest = rest.substring(at + 1);
                final int colon = userinfo.indexOf(':');
                if (colon >= 0) {
                    user = urlDecode(userinfo.substring(0, colon));
                    password = urlDecode(userinfo.substring(colon + 1));
                } else {
                    user = urlDecode(userinfo);
                }
            }
            String authority;
            String path = "";
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                authority = rest.substring(0, slash);
                path = rest.substring(slash); // leading '/' preserved
            } else {
                authority = rest;
            }
            if (authority.isEmpty()) {
                throw new SQLException("postgres sink URL missing host: " + raw);
            }
            final Map<String, String> queryParams = new LinkedHashMap<>();
            if (!query.isEmpty()) {
                for (String kv : query.split("&")) {
                    if (kv.isEmpty()) continue;
                    int eq = kv.indexOf('=');
                    if (eq >= 0) {
                        queryParams.put(urlDecode(kv.substring(0, eq)), urlDecode(kv.substring(eq + 1)));
                    } else {
                        queryParams.put(urlDecode(kv), "");
                    }
                }
            }
            final String jdbcUrl = "jdbc:postgresql://" + authority + path;
            return new ParsedPostgresUrl(jdbcUrl, user, password, queryParams);
        }

        private static String urlDecode(String s) {
            try {
                return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return s;
            }
        }
    }

    // Silence unused-import warnings for Locale in case a future change needs it.
    @SuppressWarnings("unused")
    private static void _touch(Locale l) {}
}
