package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpProject;
import org.apache.jena.sparql.expr.Expr;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Query-time conversion rules — target predicate + source predicate +
 * SPARQL expression bridging them. Loaded once at startup, shared
 * read-only across requests.
 *
 * <p>Mirrors {@code oxigraph-wf/src/conversion_registry.rs}. Each row is
 * a directed conversion: "if you asked for {@code <target>} and the
 * store only has {@code <source>}, compute {@code <target>} =
 * expr(source)."
 */
public final class ConversionRegistry {

    /** A single (target, source, expression) rule. */
    public static final class ConversionRule {
        public final String targetPredicate;
        public final String sourcePredicate;
        /** Original SPARQL scalar expression referencing {@code ?source}. */
        public final String expression;
        /** Deterministic virtual graph IRI where this rule's rows appear. */
        public final String graphIri;
        /**
         * Parsed form of {@code expression}. Parsed once at load time so
         * invalid SPARQL surfaces as a startup error, not a query-time
         * crash.
         */
        public final Expr parsedExpression;

        ConversionRule(final String targetPredicate,
                       final String sourcePredicate,
                       final String expression,
                       final String graphIri,
                       final Expr parsedExpression) {
            this.targetPredicate = targetPredicate;
            this.sourcePredicate = sourcePredicate;
            this.expression = expression;
            this.graphIri = graphIri;
            this.parsedExpression = parsedExpression;
        }
    }

    private final Map<String, List<ConversionRule>> byTarget;
    private final Map<String, ConversionRule> byGraphIri;

    private ConversionRegistry(final Map<String, List<ConversionRule>> byTarget,
                               final Map<String, ConversionRule> byGraphIri) {
        this.byTarget = Collections.unmodifiableMap(byTarget);
        this.byGraphIri = Collections.unmodifiableMap(byGraphIri);
    }

    public static ConversionRegistry empty() {
        return new ConversionRegistry(new HashMap<>(), new HashMap<>());
    }

    public boolean isEmpty() {
        return byGraphIri.isEmpty();
    }

    public int size() {
        return byGraphIri.size();
    }

    /**
     * All rules whose target predicate matches {@code iri}. Empty when
     * none registered.
     */
    public List<ConversionRule> rulesForTarget(final String iri) {
        final List<ConversionRule> r = byTarget.get(iri);
        return r == null ? Collections.emptyList() : Collections.unmodifiableList(r);
    }

    /**
     * The specific rule whose virtual graph IRI is {@code iri}. Used
     * when a query explicitly names a conversion graph.
     */
    public Optional<ConversionRule> ruleByGraph(final String iri) {
        return Optional.ofNullable(byGraphIri.get(iri));
    }

    public Collection<ConversionRule> rules() {
        return Collections.unmodifiableCollection(byGraphIri.values());
    }

    /** Build a registry from an explicit list of raw rules. Test seed. */
    public static ConversionRegistry of(final List<RawRule> raw) {
        final Map<String, List<ConversionRule>> byTarget = new HashMap<>();
        final Map<String, ConversionRule> byGraphIri = new HashMap<>();
        for (RawRule r : raw) {
            final String graphIri = mintGraphIri(r.target, r.source);
            final Expr parsed = parseExpression(r.expression);
            final ConversionRule rule = new ConversionRule(
                    r.target, r.source, r.expression, graphIri, parsed);
            byGraphIri.put(graphIri, rule);
            byTarget.computeIfAbsent(r.target, k -> new ArrayList<>()).add(rule);
        }
        return new ConversionRegistry(byTarget, byGraphIri);
    }

    /** Raw fields; the registry mints the graph IRI and parses the expression itself. */
    public static final class RawRule {
        public final String target;
        public final String source;
        public final String expression;

        public RawRule(final String target, final String source, final String expression) {
            this.target = target;
            this.source = source;
            this.expression = expression;
        }
    }

    /**
     * Load from a SQLite {@code conversions} table. Absent file or
     * absent table both yield an empty registry — first-boot before any
     * conversion has been declared is a valid state.
     */
    public static ConversionRegistry loadFromSqlite(final Path dbPath, final String table) {
        final String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            if (!tableExists(conn, table)) {
                return empty();
            }
            final List<RawRule> raw = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT target_predicate, source_predicate, expression FROM " + table)) {
                while (rs.next()) {
                    raw.add(new RawRule(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
            return of(raw);
        } catch (SQLException e) {
            throw new RuntimeException(
                    "opening conversions db at " + dbPath + ": " + e.getMessage(), e);
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

    /**
     * Mint a deterministic {@code urn:wf:conversion:*} IRI from a
     * target/source pair. Human-readable in the common case, plus a
     * 64-bit FNV hash suffix so distinct-target rules with the same
     * last segment can't collide.
     */
    static String mintGraphIri(final String target, final String source) {
        final String targetShort = shortName(target);
        final String sourceShort = shortName(source);
        final long hash = fnv64(target + "\0" + source);
        return String.format(Locale.ROOT, "urn:wf:conversion:%s_from_%s:%016x",
                targetShort, sourceShort, hash);
    }

    private static String shortName(final String iri) {
        final int hashIdx = iri.lastIndexOf('#');
        final String afterHash = hashIdx >= 0 ? iri.substring(hashIdx + 1) : iri;
        final int slashIdx = afterHash.lastIndexOf('/');
        final String afterSlash = slashIdx >= 0 ? afterHash.substring(slashIdx + 1) : afterHash;
        final int colonIdx = afterSlash.lastIndexOf(':');
        final String afterColon = colonIdx >= 0 ? afterSlash.substring(colonIdx + 1) : afterSlash;
        if (afterColon.isEmpty()) {
            return "iri";
        }
        final StringBuilder sb = new StringBuilder(afterColon.length());
        for (int i = 0; i < afterColon.length(); i++) {
            final char c = afterColon.charAt(i);
            sb.append(isAsciiAlphaNumeric(c) ? c : '_');
        }
        return sb.toString();
    }

    private static boolean isAsciiAlphaNumeric(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static long fnv64(final String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= (s.charAt(i) & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * Parse a bare SPARQL scalar expression into a Jena {@link Expr}.
     * Jena has no bare-expression entrypoint, so wrap the input in a
     * minimal {@code SELECT ((<expr>) AS ?_bind) WHERE {}} and dig the
     * Expr out of the Extend node.
     */
    static Expr parseExpression(final String text) {
        final String wrapped = "SELECT ((" + text + ") AS ?_bind) WHERE {}";
        final Query q = QueryFactory.create(wrapped);
        Op cursor = Algebra.compile(q);
        while (true) {
            if (cursor instanceof OpProject p) {
                cursor = p.getSubOp();
            } else if (cursor instanceof OpExtend e) {
                for (Map.Entry<org.apache.jena.sparql.core.Var, Expr> entry
                        : e.getVarExprList().getExprs().entrySet()) {
                    return entry.getValue();
                }
                throw new IllegalStateException("empty Extend produced by wrapper parse");
            } else {
                throw new IllegalStateException(
                        "expected OpExtend inside wrapped SELECT, got " + cursor.getClass());
            }
        }
    }
}
