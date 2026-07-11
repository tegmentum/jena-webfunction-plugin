package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Planner-side catalog of materialized shapes. Read-only after startup —
 * refresh requires a server restart.
 *
 * <p>Mirrors {@code oxigraph-wf/src/shape_registry.rs}. Each descriptor
 * declares a set of column predicates and an optional anchor class; a
 * BGP that only references one shape's predicates on a shared subject
 * variable can be rewritten to {@code SERVICE <wf:call>} against the
 * shape's sink.
 */
public final class ShapeRegistry {

    /**
     * The subset of the descriptor JSON we need for the planner rewrite.
     * Deliberately narrow — the registry doesn't validate constraints,
     * it just maps predicates &rarr; shape and holds the full descriptor
     * JSON to pass through to {@code wf_fetch}.
     */
    public static final class ShapeEntry {
        public final String name;
        public final String descriptorJson;
        public final String anchorClass;
        /** Predicate IRI &rarr; column name. */
        public final Map<String, String> columnsByPredicate;
        /** Column with role {@code subject_iri}, or {@code "id"} as a safe fallback. */
        public final String subjectColumnName;

        ShapeEntry(final String name,
                   final String descriptorJson,
                   final String anchorClass,
                   final Map<String, String> columnsByPredicate,
                   final String subjectColumnName) {
            this.name = name;
            this.descriptorJson = descriptorJson;
            this.anchorClass = anchorClass;
            this.columnsByPredicate = Collections.unmodifiableMap(columnsByPredicate);
            this.subjectColumnName = subjectColumnName;
        }
    }

    private final Map<String, ShapeEntry> byName;
    /** Predicate IRI &rarr; shape name (for O(1) BGP predicate lookup). */
    private final Map<String, String> predicateToShape;

    private ShapeRegistry(final Map<String, ShapeEntry> byName,
                          final Map<String, String> predicateToShape) {
        this.byName = Collections.unmodifiableMap(byName);
        this.predicateToShape = Collections.unmodifiableMap(predicateToShape);
    }

    public static ShapeRegistry empty() {
        return new ShapeRegistry(new HashMap<>(), new HashMap<>());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public int size() {
        return byName.size();
    }

    /** Look up a shape by any of its column predicates. */
    public Optional<ShapeEntry> findByPredicate(final String iri) {
        final String name = predicateToShape.get(iri);
        return name == null ? Optional.empty() : Optional.of(byName.get(name));
    }

    /**
     * Look up a shape by its rdf:type IRI (anchor class). Used when a
     * BGP has {@code ?s a <class>}.
     */
    public Optional<ShapeEntry> findByClass(final String iri) {
        for (ShapeEntry e : byName.values()) {
            if (iri.equals(e.anchorClass)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public Optional<ShapeEntry> shapeByName(final String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<String> shapeNames() {
        return byName.keySet();
    }

    public static ShapeRegistry of(final List<ShapeEntry> entries) {
        final Map<String, ShapeEntry> byName = new HashMap<>();
        final Map<String, String> predicateToShape = new HashMap<>();
        for (ShapeEntry e : entries) {
            byName.put(e.name, e);
            for (String pred : e.columnsByPredicate.keySet()) {
                predicateToShape.put(pred, e.name);
            }
        }
        return new ShapeRegistry(byName, predicateToShape);
    }

    /**
     * Load from an SQLite {@code shapes} table. Missing file or missing
     * table both yield an empty registry — first boot before any
     * materialization has run is a valid state.
     */
    public static ShapeRegistry loadFromSqlite(final Path dbPath, final String table) {
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            if (!tableExists(conn, table)) {
                return empty();
            }
            final Map<String, ShapeEntry> byName = new HashMap<>();
            final Map<String, String> predicateToShape = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name, descriptor FROM " + table)) {
                while (rs.next()) {
                    final String name = rs.getString(1);
                    final String descriptorJson = rs.getString(2);
                    final ShapeEntry entry = parseEntry(name, descriptorJson);
                    for (String pred : entry.columnsByPredicate.keySet()) {
                        predicateToShape.put(pred, name);
                    }
                    byName.put(name, entry);
                }
            }
            return new ShapeRegistry(byName, predicateToShape);
        } catch (SQLException e) {
            throw new RuntimeException(
                    "opening shape registry at " + dbPath + ": " + e.getMessage(), e);
        }
    }

    private static boolean tableExists(final Connection conn, final String table)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** Parse a descriptor JSON blob into a ShapeEntry. Public so tests can seed the registry. */
    public static ShapeEntry parseEntry(final String name, final String descriptorJson) {
        final JsonObject d = JSON.parse(descriptorJson);
        final String anchorClass = optionalString(
                d.hasKey("anchor") ? d.get("anchor").getAsObject() : null, "class");
        final Map<String, String> columnsByPredicate = new HashMap<>();
        String subjectColumn = "id";
        final JsonArray columns = d.hasKey("columns") ? d.get("columns").getAsArray() : new JsonArray();
        for (JsonValue jv : columns) {
            if (!jv.isObject()) continue;
            final JsonObject col = jv.getAsObject();
            final String role = optionalString(col, "role");
            final String colName = optionalString(col, "name");
            final String predicate = optionalString(col, "predicate");
            if ("subject_iri".equals(role) && colName != null) {
                subjectColumn = colName;
                continue;
            }
            if (predicate != null && colName != null) {
                columnsByPredicate.put(predicate, colName);
            }
        }
        return new ShapeEntry(name, descriptorJson, anchorClass, columnsByPredicate, subjectColumn);
    }

    private static String optionalString(final JsonObject obj, final String field) {
        if (obj == null || !obj.hasKey(field)) return null;
        final JsonValue v = obj.get(field);
        return v.isString() ? v.getAsString().value() : null;
    }

    /** Sorted list of predicate IRIs registered anywhere; convenience for tests. */
    public List<String> allPredicates() {
        return predicateToShape.keySet().stream().sorted().collect(Collectors.toList());
    }
}
