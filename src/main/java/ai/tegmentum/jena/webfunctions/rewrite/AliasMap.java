package ai.tegmentum.jena.webfunctions.rewrite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Global alias &rarr; canonical IRI map. Loaded once at server startup and
 * shared read-only across requests.
 *
 * <p>Mirrors {@code oxigraph-wf/src/alias_rewrite.rs::AliasMap} — the SQLite
 * loader accepts a missing table (first boot before {@code wf_canonicalize}
 * has ever run) and returns an empty map in that case.
 */
public final class AliasMap {

    private final Map<String, String> aliases;

    private AliasMap(final Map<String, String> aliases) {
        this.aliases = Collections.unmodifiableMap(aliases);
    }

    /** Empty map — same effect as running without {@code --alias-map}. */
    public static AliasMap empty() {
        return new AliasMap(new HashMap<>());
    }

    /** Build a map from an explicit alias &rarr; canonical dictionary. */
    public static AliasMap of(final Map<String, String> aliases) {
        return new AliasMap(new HashMap<>(aliases));
    }

    /**
     * Populate from a SQLite database's {@code aliases} table. Missing table
     * (first-boot before canonicalize has ever run) is not an error; callers
     * get an empty map.
     */
    public static AliasMap loadFromSqlite(final Path dbPath, final String table) {
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            if (!tableExists(conn, table)) {
                return AliasMap.empty();
            }
            final Map<String, String> loaded = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT alias, canonical FROM " + table)) {
                while (rs.next()) {
                    loaded.put(rs.getString(1), rs.getString(2));
                }
            }
            return new AliasMap(loaded);
        } catch (SQLException e) {
            throw new RuntimeException(
                    "opening alias db at " + dbPath + ": " + e.getMessage(), e);
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

    public int size() {
        return aliases.size();
    }

    public boolean isEmpty() {
        return aliases.isEmpty();
    }

    /**
     * Look up an alias's canonical form. Returns empty for IRIs that aren't
     * aliased (the vast majority in a well-behaved dataset).
     */
    public Optional<String> canonicalOf(final String alias) {
        return Optional.ofNullable(aliases.get(alias));
    }
}
