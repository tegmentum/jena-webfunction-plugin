package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Planner-side catalog of federated SPARQL / substrate sources.
 * Consulted by {@link WfFederationRewrite} during the federation pass
 * (design memo &sect;03 and &sect;04 of
 * {@code wf-conformance/docs/design/wf-federation.md}).
 *
 * <p>Each entry names one federatable source together with its
 * <em>dispatch shape</em>:
 *
 * <ul>
 *   <li>{@link SourceType#SPARQL} / {@link SourceType#HTTP_SPARQL}
 *       &mdash; standard SPARQL 1.1 protocol POST against
 *       {@link FederationSource#endpoint()}.</li>
 *   <li>{@link SourceType#WF_SEARCH} / {@link SourceType#WF_FETCH} /
 *       {@link SourceType#WF_DOCUMENT} &mdash; substrate URL sugar
 *       (e.g. {@code wf-search:<name>}). The federation pass emits the
 *       substrate SERVICE URL; the URL-sugar rewrite (memo &sect;06)
 *       then expands it into a {@code wf-invoke:} dispatch.</li>
 * </ul>
 *
 * <p>v0.2 additions (design memo &sect;02 probe mode, &sect;07 cost
 * model):
 *
 * <ul>
 *   <li>{@link #probeMode()} &mdash; flip the pass into ASK-probe
 *       discovery. On top of declared-predicate lookup, the pass asks
 *       every registered source whether it covers a predicate via
 *       {@code ASK { ?s <p> ?o }}.</li>
 *   <li>{@link #probeTtlSecs()} &mdash; registry-wide default TTL for
 *       the probe cache. Per-source
 *       {@link FederationSource#probeTtlSecs()} overrides this default.</li>
 *   <li>{@link #probeCache()} &mdash; in-memory
 *       {@code (source, predicate) -> (bool, when)} cache. Populated on
 *       the first probe, consulted by later plans within the TTL window.</li>
 *   <li>Cost model plumbing lives on {@link FederationSource} itself
 *       ({@code cardinalityHint} / {@code cardinalityHints}); the
 *       registry just preserves and returns them.</li>
 * </ul>
 *
 * <p>Read-only after load (except for the probe cache and injected probe
 * function, which are mutable by design); an empty registry (no config
 * flag) is a valid state and is what {@link WfFederationRewrite} treats
 * as an unconditional no-op. Identical lifecycle to
 * {@link FulltextRegistry} and {@link DocumentRegistry}.
 */
public final class FederationRegistry {

    /** Discriminates dispatch shape at plan time (memo &sect;03). */
    public enum SourceType {
        /** Plain SPARQL 1.1 endpoint. */
        SPARQL,
        /**
         * Substrate {@code wf-search:} URL sugar. The federation pass
         * emits {@code SERVICE <wf-search:<name>>} which the wf-search
         * rewrite (memo &sect;06 of wf-document-v1) then expands.
         */
        WF_SEARCH,
        /** Substrate {@code wf-fetch:} URL sugar. */
        WF_FETCH,
        /** Substrate {@code wf-document:} URL sugar. */
        WF_DOCUMENT,
        /**
         * Substrate {@code wf-vector:} URL sugar
         * (wf-conformance/docs/design/wf-vector.md &sect;04). The
         * federation pass emits {@code SERVICE <wf-vector:<name>?query=...&k=...>};
         * in v0.1 only Oxigraph has a native embedded vector index that
         * folds the URL further, so on Jena the URL stays unfolded and
         * the query will error unless a wf-vector-capable backend is
         * federated in some other way. Declared as a first-class source
         * type so cases can register a WfVector source uniformly across
         * engines (memo &sect;10 defers native co-located indexes on
         * Jena / RDF4J / QLever to v0.2+).
         */
        WF_VECTOR,
        /**
         * Substrate {@code wf-relational:} URL sugar (wf-relational
         * design memo &sect;04). {@link FederationSource#endpoint()}
         * holds a {@code postgres://…/db} URL &mdash; Postgres is the
         * only v0.1 backend. The federation pass emits
         * {@code SERVICE <wf-relational:<name>>} and hands dispatch to
         * {@code wf_fetch}, whose shape descriptor's
         * {@code sink_kind = "postgres"} tells the guest to speak
         * Postgres-SQL. Jena ships the registry plumbing; end-to-end
         * dispatch is gated on the host sink layer growing a Postgres
         * backend (memo &sect;11 step 2).
         */
        WF_RELATIONAL,
        /**
         * Substrate {@code wf-sagegraph:} URL sugar
         * (wf-conformance/docs/design/wf-sagegraph.md &sect;04). The
         * federation pass emits
         * {@code SERVICE <wf-sagegraph:<name>?node=<uri>&k=<n>>}; Jena
         * has no native sagegraph embedder in v0.1, so the URL stays
         * unfolded and the query will error unless a wf-sagegraph-
         * capable backend is federated in some other way. Declared as a
         * first-class source type so cases can register a WfSageGraph
         * source uniformly across engines (memo &sect;13 defers real ML
         * to v0.2+).
         */
        WF_SAGEGRAPH,
        /**
         * External HTTP-hosted SPARQL endpoint. Dispatched identically
         * to {@link #SPARQL} at v0.1; kept distinct so operators can
         * flag high-latency / low-availability sources for the v0.2
         * cost model (memo &sect;07).
         */
        HTTP_SPARQL
    }

    private final List<FederationSource> sources;
    /** Name &rarr; index-in-{@code sources} for administrative lookup. */
    private final Map<String, Integer> nameToIndex;
    /**
     * Predicate IRI &rarr; ordered list of source indices. A predicate
     * that lives in multiple sources is honestly reported as multi-
     * source; the federation pass turns those into an OpUnion over
     * per-source OpService clauses (memo &sect;04 step 2).
     */
    private final Map<String, List<Integer>> predicateToIndices;

    /**
     * v0.2 probe-mode toggle. When true,
     * {@link #findByPredicateProbing(String)} consults the probe cache
     * (and issues fresh ASK queries when the cache is cold) in addition
     * to the static predicate index.
     */
    private boolean probeMode;
    /**
     * Registry-wide default probe TTL, in seconds. Per-source
     * {@link FederationSource#probeTtlSecs()} overrides this when set.
     */
    private long probeTtlSecs;
    /**
     * Per-registry probe cache. Shared across plans on the same server
     * process so probes populated by an earlier query show up in later
     * ones within the TTL window.
     */
    private final ProbeCache probeCache;
    /**
     * Injectable probe function &mdash; tests pass a mock without
     * spinning up an HTTP listener; production wires an ASK-based probe.
     * When {@code null}, {@link #probePredicate} throws
     * {@link IllegalStateException}.
     */
    private ProbeFn probeFn;

    private FederationRegistry(final List<FederationSource> sources,
                               final Map<String, Integer> nameToIndex,
                               final Map<String, List<Integer>> predicateToIndices,
                               final boolean probeMode,
                               final long probeTtlSecs) {
        this.sources = List.copyOf(sources);
        this.nameToIndex = Map.copyOf(nameToIndex);
        // Freeze inner lists so consumers can't mutate the reverse index.
        final Map<String, List<Integer>> frozen = new HashMap<>(predicateToIndices.size());
        for (Map.Entry<String, List<Integer>> e : predicateToIndices.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.predicateToIndices = Map.copyOf(frozen);
        this.probeMode = probeMode;
        this.probeTtlSecs = probeTtlSecs;
        this.probeCache = new ProbeCache();
        this.probeFn = null;
    }

    /**
     * Empty registry &mdash; the uninstrumented-startup state. Every
     * lookup is a no-op; {@link #isEmpty()} returns true.
     */
    public static FederationRegistry empty() {
        return new FederationRegistry(List.of(), Map.of(), Map.of(), false, 3600L);
    }

    public boolean isEmpty() { return sources.isEmpty(); }
    public int size()        { return sources.size(); }

    /** All registered sources in declaration order. */
    public List<FederationSource> sources() { return sources; }

    /** Administrative lookup &mdash; resolve a source by its {@code name} field. */
    public FederationSource byName(final String name) {
        final Integer idx = nameToIndex.get(name);
        return idx == null ? null : sources.get(idx);
    }

    /**
     * All sources that declare {@code predicateIri}. Multiple sources
     * can legitimately declare the same predicate (federated
     * partitioning by subject range, mirroring across regions, etc.);
     * v0.1 handles that with an honest {@code OpUnion} at rewrite time.
     *
     * <p>Empty list means "no registered source covers this predicate"
     * &mdash; the federation pass leaves such triples in place so the
     * local dataset (or a caller-provided SERVICE) can answer them.
     */
    public List<FederationSource> findByPredicate(final String predicateIri) {
        final List<Integer> indices = predicateToIndices.get(predicateIri);
        if (indices == null) return List.of();
        final List<FederationSource> out = new ArrayList<>(indices.size());
        for (int i : indices) out.add(sources.get(i));
        return Collections.unmodifiableList(out);
    }

    /** v0.2 probe-mode toggle accessor. */
    public boolean probeMode() { return probeMode; }

    /** v0.2 registry-wide default TTL in seconds. */
    public long probeTtlSecs() { return probeTtlSecs; }

    /**
     * The shared probe cache. Callers that want to warm the cache
     * ahead of a batch of plans (or to inspect it in tests) borrow it
     * here.
     */
    public ProbeCache probeCache() { return probeCache; }

    /**
     * Install a probe function. Chained builder &mdash; production
     * wires an ASK-based probe at server startup; tests wire a mock.
     * Returns {@code this} for fluent chaining.
     */
    public FederationRegistry withProbeFn(final ProbeFn fn) {
        this.probeFn = fn;
        return this;
    }

    /**
     * Turn probe mode on or off manually (usually flipped from JSON via
     * {@code probe_mode: true}; this accessor is for programmatic
     * construction e.g. in unit tests). Returns {@code this} for fluent
     * chaining.
     */
    public FederationRegistry withProbeMode(final boolean on) {
        this.probeMode = on;
        return this;
    }

    /**
     * v0.2 probe entry point &mdash; check whether {@code source}
     * covers {@code predicateIri}. Consults the cache first (with the
     * per-source TTL when set, else the registry-wide default); on miss
     * issues an ASK query via the injected probe function and caches
     * the result. Throws when the probe function is unset or when the
     * probe itself fails (transport/protocol failure) &mdash; callers
     * should treat a thrown exception as "skip this source for this
     * plan" per memo &sect;04.
     */
    public boolean probePredicate(final FederationSource source,
                                  final String predicateIri) throws Exception {
        final Duration ttl = source.probeTtlSecs().isPresent()
                ? Duration.ofSeconds(source.probeTtlSecs().getAsInt())
                : Duration.ofSeconds(probeTtlSecs);
        final Optional<Boolean> hit = probeCache.get(source.name(), predicateIri, ttl);
        if (hit.isPresent()) {
            return hit.get();
        }
        if (probeFn == null) {
            throw new IllegalStateException("no probe function configured");
        }
        final boolean has = probeFn.probe(source, predicateIri);
        probeCache.put(source.name(), predicateIri, has);
        return has;
    }

    /**
     * v0.2 probe-mode augmented find. Same shape as
     * {@link #findByPredicate(String)} (statically-declared coverage),
     * and when {@link #probeMode()} is on also asks every registered
     * source without declared coverage via ASK probes. Silently drops
     * sources whose probe fails (endpoint down / auth) &mdash; memo
     * &sect;04 skip-and-log.
     */
    public List<FederationSource> findByPredicateProbing(final String predicateIri) {
        final List<FederationSource> hits = new ArrayList<>(findByPredicate(predicateIri));
        if (!probeMode) {
            return Collections.unmodifiableList(hits);
        }
        final Set<String> already = new HashSet<>();
        for (FederationSource s : hits) already.add(s.name());
        for (FederationSource entry : sources) {
            if (already.contains(entry.name())) continue;
            try {
                if (probePredicate(entry, predicateIri)) {
                    hits.add(entry);
                }
            } catch (Exception e) {
                // Probe failure: skip this source for this plan (memo
                // §04). Log at stderr so operators can trace flap
                // patterns without the log flooding.
                System.err.println("wf_federation probe: source `" + entry.name()
                        + "` predicate `" + predicateIri + "` failed: " + e.getMessage());
            }
        }
        return Collections.unmodifiableList(hits);
    }

    /**
     * Build a registry from in-memory sources. Convenient for tests and
     * non-JSON loaders. Duplicate {@link FederationSource#name() names}
     * throw {@link IllegalArgumentException}; the predicate index is
     * derived automatically.
     */
    public static FederationRegistry of(final Iterable<FederationSource> sourcesIn) {
        final List<FederationSource> list = new ArrayList<>();
        final Map<String, Integer> nameToIndex = new HashMap<>();
        // LinkedHashMap keeps the multi-source order deterministic in
        // declaration order; matters for stable UNION-branch layout.
        final Map<String, List<Integer>> predIndex = new LinkedHashMap<>();
        for (FederationSource s : sourcesIn) {
            final int idx = list.size();
            if (nameToIndex.put(s.name(), idx) != null) {
                throw new IllegalArgumentException(
                        "federation registry source `" + s.name() + "`: duplicate name");
            }
            for (String pred : s.predicates()) {
                predIndex.computeIfAbsent(pred, k -> new ArrayList<>()).add(idx);
            }
            list.add(s);
        }
        return new FederationRegistry(list, nameToIndex, predIndex, false, 3600L);
    }

    /**
     * Load a registry from the JSON shape declared in &sect;03 of the
     * design memo. An absent file is an error; empty-registry semantics
     * are what {@link #empty()} (no CLI flag) gives you, not what a
     * missing config file gives you.
     */
    public static FederationRegistry loadFromJson(final Path path) throws IOException {
        final String text = Files.readString(path);
        final JsonObject root;
        try {
            root = JSON.parse(text);
        } catch (RuntimeException e) {
            throw new IOException(
                    "parsing federation registry at " + path + ": " + e.getMessage(), e);
        }
        return fromJson(root);
    }

    /**
     * Parse a registry from an already-decoded {@link JsonObject}.
     * Extracted so unit tests can drive the parser without hitting the
     * filesystem.
     */
    public static FederationRegistry fromJson(final JsonObject root) {
        final List<FederationSource> parsed = new ArrayList<>();
        if (root.hasKey("sources")) {
            final JsonValue srcVal = root.get("sources");
            if (srcVal.isArray()) {
                for (JsonValue raw : srcVal.getAsArray()) {
                    if (!raw.isObject()) continue;
                    parsed.add(parseSource(raw.getAsObject()));
                }
            }
        }
        final boolean probeMode;
        if (hasNonNull(root, "probe_mode")) {
            final JsonValue node = root.get("probe_mode");
            if (!node.isBoolean()) {
                throw new IllegalArgumentException(
                        "federation registry: `probe_mode` must be a boolean");
            }
            probeMode = node.getAsBoolean().value();
        } else {
            probeMode = false;
        }
        final long probeTtlSecs;
        if (hasNonNull(root, "probe_ttl_secs")) {
            probeTtlSecs = root.get("probe_ttl_secs").getAsNumber().value().longValue();
        } else {
            probeTtlSecs = 3600L;
        }
        // Reuse `of()` for the source-side validation, then apply
        // probe-mode fields.
        final FederationRegistry base = of(parsed);
        base.probeMode = probeMode;
        base.probeTtlSecs = probeTtlSecs;
        return base;
    }

    private static FederationSource parseSource(final JsonObject raw) {
        final String name;
        if (hasNonNull(raw, "name") && raw.get("name").isString()) {
            name = raw.get("name").getAsString().value();
        } else {
            throw new IllegalArgumentException(
                    "federation registry source missing required field `name`");
        }
        if (!hasNonNull(raw, "type")) {
            throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: missing required field `type`");
        }
        final String typeStr = raw.get("type").getAsString().value();
        final SourceType type = parseType(name, typeStr);

        if (!hasNonNull(raw, "endpoint") || !raw.get("endpoint").isString()) {
            throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: missing required field `endpoint`");
        }
        final String endpoint = raw.get("endpoint").getAsString().value();

        final List<String> predicates = new ArrayList<>();
        if (raw.hasKey("predicates")) {
            final JsonValue predsNode = raw.get("predicates");
            if (predsNode.isArray()) {
                for (JsonValue p : predsNode.getAsArray()) {
                    if (p.isString()) predicates.add(p.getAsString().value());
                }
            }
        }

        final OptionalInt probeTtl;
        if (hasNonNull(raw, "probe_ttl_secs")) {
            probeTtl = OptionalInt.of(
                    raw.get("probe_ttl_secs").getAsNumber().value().intValue());
        } else {
            probeTtl = OptionalInt.empty();
        }

        final Optional<Boolean> silent;
        if (hasNonNull(raw, "silent")) {
            final JsonValue silentNode = raw.get("silent");
            if (!silentNode.isBoolean()) {
                throw new IllegalArgumentException(
                        "federation registry source `" + name
                                + "`: `silent` must be a boolean");
            }
            silent = Optional.of(silentNode.getAsBoolean().value());
        } else {
            silent = Optional.empty();
        }

        final OptionalLong cardinalityHint;
        if (hasNonNull(raw, "cardinality_hint")) {
            cardinalityHint = OptionalLong.of(
                    raw.get("cardinality_hint").getAsNumber().value().longValue());
        } else {
            cardinalityHint = OptionalLong.empty();
        }

        final Map<String, Long> cardinalityHints;
        if (hasNonNull(raw, "cardinality_hints")) {
            final JsonValue node = raw.get("cardinality_hints");
            if (!node.isObject()) {
                throw new IllegalArgumentException(
                        "federation registry source `" + name
                                + "`: `cardinality_hints` must be an object");
            }
            final Map<String, Long> hints = new LinkedHashMap<>();
            final JsonObject obj = node.getAsObject();
            for (String key : obj.keys()) {
                hints.put(key, obj.get(key).getAsNumber().value().longValue());
            }
            cardinalityHints = hints;
        } else {
            cardinalityHints = Map.of();
        }

        final Optional<RelationalConfig> relationalConfig;
        if (hasNonNull(raw, "relational")) {
            final JsonValue rel = raw.get("relational");
            if (!rel.isObject()) {
                throw new IllegalArgumentException(
                        "federation registry source `" + name
                                + "`: `relational` must be an object");
            }
            relationalConfig = Optional.of(parseRelationalConfig(name, rel.getAsObject()));
        } else {
            relationalConfig = Optional.empty();
        }

        return new FederationSource(name, type, endpoint, predicates, probeTtl,
                silent, cardinalityHint, cardinalityHints, relationalConfig);
    }

    /**
     * Parse the {@code relational} extension block into a
     * {@link RelationalConfig}. Rejects a descriptor missing any of the
     * three required fields (sink_kind / table / subject_column) so
     * malformed configs fail loudly at load time rather than silently at
     * plan time; that matches the base parser's fail-loud rules for
     * required source fields.
     */
    private static RelationalConfig parseRelationalConfig(final String sourceName,
                                                          final JsonObject obj) {
        final String sinkKind = requiredString(sourceName, obj, "sink_kind");
        final String table = requiredString(sourceName, obj, "table");
        final String subjectColumn = requiredString(sourceName, obj, "subject_column");

        final String anchorClass;
        if (hasNonNull(obj, "anchor") && obj.get("anchor").isObject()) {
            final JsonObject a = obj.get("anchor").getAsObject();
            if (hasNonNull(a, "class") && a.get("class").isString()) {
                anchorClass = a.get("class").getAsString().value();
            } else {
                anchorClass = null;
            }
        } else {
            anchorClass = null;
        }

        final List<Column> columns = new ArrayList<>();
        if (obj.hasKey("columns") && obj.get("columns").isArray()) {
            for (JsonValue cv : obj.get("columns").getAsArray()) {
                if (!cv.isObject()) continue;
                final JsonObject co = cv.getAsObject();
                final String colName = hasNonNull(co, "name") && co.get("name").isString()
                        ? co.get("name").getAsString().value() : null;
                final String role = hasNonNull(co, "role") && co.get("role").isString()
                        ? co.get("role").getAsString().value() : null;
                if (colName == null || role == null) continue;
                final String predicate = hasNonNull(co, "predicate") && co.get("predicate").isString()
                        ? co.get("predicate").getAsString().value() : null;
                final String xsdType = hasNonNull(co, "type") && co.get("type").isString()
                        ? co.get("type").getAsString().value() : null;
                columns.add(new Column(colName, role, predicate, xsdType));
            }
        }

        final String iriTemplate;
        if (hasNonNull(obj, "iri_template") && obj.get("iri_template").isString()) {
            iriTemplate = obj.get("iri_template").getAsString().value();
        } else {
            iriTemplate = null;
        }

        final boolean emitProvenance;
        if (hasNonNull(obj, "emit_provenance") && obj.get("emit_provenance").isBoolean()) {
            emitProvenance = obj.get("emit_provenance").getAsBoolean().value();
        } else {
            emitProvenance = false;
        }

        final String schemaVersion;
        if (hasNonNull(obj, "schema_version") && obj.get("schema_version").isString()) {
            schemaVersion = obj.get("schema_version").getAsString().value();
        } else {
            schemaVersion = null;
        }

        return new RelationalConfig(sinkKind, table, subjectColumn,
                anchorClass, columns, iriTemplate, emitProvenance, schemaVersion);
    }

    private static String requiredString(final String sourceName,
                                         final JsonObject obj,
                                         final String field) {
        if (!hasNonNull(obj, field) || !obj.get(field).isString()) {
            throw new IllegalArgumentException(
                    "federation registry source `" + sourceName
                            + "`: `relational." + field + "` is required");
        }
        return obj.get(field).getAsString().value();
    }

    private static SourceType parseType(final String name, final String typeStr) {
        // Accept both hyphenated (memo shape) and underscored spellings
        // for parity with the Rust/RDF4J parsers.
        return switch (typeStr) {
            case "sparql", "SPARQL" -> SourceType.SPARQL;
            case "wf-search", "wf_search", "WfSearch" -> SourceType.WF_SEARCH;
            case "wf-fetch", "wf_fetch", "WfFetch" -> SourceType.WF_FETCH;
            case "wf-document", "wf_document", "WfDocument" -> SourceType.WF_DOCUMENT;
            case "wf-vector", "wf_vector", "WfVector" -> SourceType.WF_VECTOR;
            case "wf-relational", "wf_relational", "WfRelational" -> SourceType.WF_RELATIONAL;
            case "wf-sagegraph", "wf_sagegraph", "WfSageGraph" -> SourceType.WF_SAGEGRAPH;
            case "http-sparql", "http_sparql", "HttpSparql" -> SourceType.HTTP_SPARQL;
            default -> throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: unknown type `" + typeStr
                            + "` (expected `sparql`, `wf-search`, `wf-fetch`, `wf-document`, "
                            + "`wf-vector`, `wf-relational`, `wf-sagegraph`, or `http-sparql`)");
        };
    }

    /** Presence-and-non-null helper &mdash; atlas.json has no equivalent of Jackson's {@code hasNonNull}. */
    private static boolean hasNonNull(final JsonObject obj, final String key) {
        if (!obj.hasKey(key)) return false;
        final JsonValue v = obj.get(key);
        return v != null && !v.isNull();
    }

    // ---------------------------------------------------------------------
    // v0.2 Probe mode &mdash; ASK-cache discovery of predicate coverage.
    //
    // Design memo §02 (two modes: static / probe), §10 (v0.2 wire the
    // COUNT-star + ASK probes). When probeMode is true on the registry,
    // a predicate that no source statically declares gets tested per
    // source via `ASK { ?s <predicate> ?o }`. Positive results extend
    // the find-by-predicate view for the plan; results cache per
    // (source, predicate) with a TTL so subsequent plans within the
    // window skip the round-trip.
    // ---------------------------------------------------------------------

    /**
     * Injectable probe function. Given {@code (source, predicate)},
     * returns whether the source covers the predicate. Throws to signal
     * transport/protocol failure &mdash; the rewrite pass treats a
     * thrown exception as "skip this source for this plan" per memo
     * &sect;04 (probe-error &rarr; conservative skip).
     */
    @FunctionalInterface
    public interface ProbeFn {
        boolean probe(FederationSource src, String predicateIri) throws Exception;
    }

    /** Composite key for the probe cache. */
    public record CacheKey(String source, String predicate) {}

    /** Cached probe result plus timestamp for TTL comparison. */
    public record CacheEntry(boolean hasIt, Instant when) {}

    /**
     * Per-registry probe cache. Keyed by {@code (source, predicate)};
     * value carries the observed boolean plus the {@link Instant} of
     * observation. TTL is applied on read via
     * {@link #get(String, String, Duration)}. Wrapped in a lock &mdash;
     * probe checks are rare compared to plan-time reads, so contention
     * is a non-issue.
     */
    public static final class ProbeCache {
        private final Map<CacheKey, CacheEntry> entries = new HashMap<>();
        private final Object lock = new Object();

        /**
         * Cache lookup &mdash; returns a present {@code Optional<Boolean>}
         * when a non-expired entry exists; empty when absent or expired
         * (caller re-probes).
         */
        public Optional<Boolean> get(final String source, final String predicate,
                                     final Duration ttl) {
            synchronized (lock) {
                final CacheEntry e = entries.get(new CacheKey(source, predicate));
                if (e == null) return Optional.empty();
                if (Duration.between(e.when(), Instant.now()).compareTo(ttl) > 0) {
                    return Optional.empty();
                }
                return Optional.of(e.hasIt());
            }
        }

        /**
         * Store a probe result. Callers should hold this across the
         * plan so subsequent identical probes within the TTL avoid the
         * round-trip.
         */
        public void put(final String source, final String predicate, final boolean has) {
            synchronized (lock) {
                entries.put(new CacheKey(source, predicate), new CacheEntry(has, Instant.now()));
            }
        }

        /** Total entry count after any expiries &mdash; test helper. */
        public int size() {
            synchronized (lock) {
                return entries.size();
            }
        }
    }

    /**
     * One registered federated source. Mirrors the JSON shape declared
     * in &sect;03 of the design memo.
     */
    public static final class FederationSource {
        private final String name;
        private final SourceType sourceType;
        private final String endpoint;
        private final List<String> predicates;
        private final OptionalInt probeTtlSecs;
        private final Optional<Boolean> silent;
        private final OptionalLong cardinalityHint;
        private final Map<String, Long> cardinalityHints;
        private final Optional<RelationalConfig> relationalConfig;

        /**
         * Legacy five-arg constructor kept for callers that don't need
         * to specify a silent override or cardinality hints. Delegates
         * to the full constructor with {@link Optional#empty()} /
         * empty defaults.
         */
        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs) {
            this(name, sourceType, endpoint, predicates, probeTtlSecs,
                    Optional.empty(), OptionalLong.empty(), Map.of(),
                    Optional.empty());
        }

        /**
         * Legacy six-arg constructor kept for callers that specify a
         * silent override but no cardinality hints. Delegates to the
         * full constructor with empty cardinality defaults.
         */
        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs,
                                final Optional<Boolean> silent) {
            this(name, sourceType, endpoint, predicates, probeTtlSecs,
                    silent, OptionalLong.empty(), Map.of(), Optional.empty());
        }

        /**
         * Legacy eight-arg constructor kept for callers that don't
         * ship a {@link RelationalConfig}. Delegates to the full
         * constructor with {@link Optional#empty()}.
         */
        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs,
                                final Optional<Boolean> silent,
                                final OptionalLong cardinalityHint,
                                final Map<String, Long> cardinalityHints) {
            this(name, sourceType, endpoint, predicates, probeTtlSecs,
                    silent, cardinalityHint, cardinalityHints, Optional.empty());
        }

        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs,
                                final Optional<Boolean> silent,
                                final OptionalLong cardinalityHint,
                                final Map<String, Long> cardinalityHints,
                                final Optional<RelationalConfig> relationalConfig) {
            this.name = name;
            this.sourceType = sourceType;
            this.endpoint = endpoint;
            this.predicates = List.copyOf(predicates);
            this.probeTtlSecs = probeTtlSecs;
            this.silent = silent;
            this.cardinalityHint = cardinalityHint;
            this.cardinalityHints = Map.copyOf(cardinalityHints);
            this.relationalConfig = relationalConfig;
        }

        public String name()              { return name; }
        public SourceType sourceType()    { return sourceType; }
        /**
         * Dispatch endpoint. For SPARQL / HTTP_SPARQL this is the raw
         * URL the federation pass emits inside the {@code SERVICE}
         * clause; for the substrate types it's typically the URL-sugar
         * URI (e.g. {@code wf-search:manuals}) already, but the pass
         * synthesizes the sugar from {@link #name()} regardless.
         */
        public String endpoint()          { return endpoint; }
        public List<String> predicates()  { return predicates; }
        /**
         * v0.2 ASK-probe cache TTL. {@link OptionalInt#empty()} means
         * "use the registry-wide default" &mdash; see
         * {@link FederationRegistry#probeTtlSecs()}.
         */
        public OptionalInt probeTtlSecs() { return probeTtlSecs; }
        /**
         * Optional override for {@code SERVICE SILENT} semantics on this
         * source's emitted {@code SERVICE} clauses.
         * {@link Optional#empty()} means "use the per-source-type
         * default" &mdash; SPARQL / HTTP_SPARQL default to silent
         * (network endpoints; transport errors degrade to empty
         * bindings without probing); WF_SEARCH / WF_FETCH / WF_DOCUMENT
         * default to non-silent (substrate-local dispatch; a failure is
         * a real bug the operator should see). Explicit value wins.
         * See design memo &sect;08 for the resolution rule.
         */
        public Optional<Boolean> silent() { return silent; }
        /**
         * v0.2 cost model &mdash; source-wide cardinality hint
         * (approximate row count the source is expected to return per
         * pattern). {@link OptionalLong#empty()} means "unknown"
         * and unknown-cardinality sources sort last in the rewrite
         * pass. Per-predicate hints in {@link #cardinalityHints()} win
         * over this source-wide value when they match.
         */
        public OptionalLong cardinalityHint() { return cardinalityHint; }
        /**
         * v0.2 cost model &mdash; per-predicate cardinality hints. The
         * rewrite pass consults this map before falling back to
         * {@link #cardinalityHint()}. Keys are predicate IRIs.
         */
        public Map<String, Long> cardinalityHints() { return cardinalityHints; }

        /**
         * v0.2 cost model &mdash; best cardinality estimate for
         * {@code predicateIri} on this source. Per-predicate hint wins
         * over source-wide hint. Returns empty when neither is set
         * &mdash; callers treat that as "unknown, sort last".
         */
        public OptionalLong cardinalityFor(final String predicateIri) {
            final Long perPred = cardinalityHints.get(predicateIri);
            if (perPred != null) return OptionalLong.of(perPred);
            return cardinalityHint;
        }

        /**
         * v0.3 extension &mdash; {@code wf-relational} source shape
         * descriptor. Populated from the JSON top-level {@code relational}
         * key on the source entry (adapter renders it in
         * {@code render_relational_descriptor}). {@link Optional#empty()}
         * for every non-{@code wf-relational} source, and also for
         * {@code wf-relational} sources that ship without a descriptor
         * block (the {@link WfRelationalRewrite} pass short-circuits
         * those).
         *
         * <p>Design memo: {@code wf-conformance/docs/design/wf-relational.md}
         * &sect;04. Prior to v0.3 this lived in a sidecar
         * {@code WfRelationalRegistry}; the sidecar was folded into
         * {@code FederationSource} so all per-source state travels
         * together. Future extension source types ({@code wf-vector},
         * {@code wf-search}) will follow the same pattern &mdash;
         * first-class optional field, discoverable at the type level.
         */
        public Optional<RelationalConfig> relationalConfig() { return relationalConfig; }
    }

    // ---------------------------------------------------------------------
    // v0.3 wf-relational shape descriptor.
    //
    // The federation-config JSON's `wf-relational` sources carry an
    // optional `relational` block that the WfRelationalRewrite pass needs
    // to build the wf_fetch descriptor (adapter emits it under the key
    // `relational`). Prior to v0.3 this lived in a sibling
    // WfRelationalRegistry that re-parsed the same file; folding it into
    // FederationSource unifies the two registries so all per-source state
    // travels together and future extension source types (wf-vector,
    // wf-search) can follow the same pattern.
    // ---------------------------------------------------------------------

    /**
     * Shape descriptor block for a {@code wf-relational} federation
     * source. Mirrors the JSON the adapter emits under the {@code relational}
     * key on the source entry &mdash; see
     * {@code wf-conformance/src/adapter/mod.rs::render_relational_descriptor}.
     *
     * <p>Kept as a proper type (not an opaque JSON string) so the rewrite
     * pass gets typed access to {@link #columnsByPredicate()} for the BGP
     * fold.
     */
    public static final class RelationalConfig {
        private final String sinkKind;
        private final String table;
        private final String subjectColumn;
        private final Anchor anchor;
        private final List<Column> columns;
        private final String iriTemplate;
        private final boolean emitProvenance;
        private final String schemaVersion;

        public RelationalConfig(final String sinkKind,
                                final String table,
                                final String subjectColumn,
                                final String anchorClass,
                                final List<Column> columns,
                                final String iriTemplate,
                                final boolean emitProvenance,
                                final String schemaVersion) {
            this.sinkKind = sinkKind;
            this.table = table;
            this.subjectColumn = subjectColumn;
            this.anchor = new Anchor(anchorClass);
            this.columns = List.copyOf(columns);
            this.iriTemplate = iriTemplate;
            this.emitProvenance = emitProvenance;
            this.schemaVersion = schemaVersion;
        }

        public String sinkKind()                { return sinkKind; }
        public String table()                   { return table; }
        public String subjectColumn()           { return subjectColumn; }
        public Anchor anchor()                  { return anchor; }
        public List<Column> columns()           { return columns; }
        public Optional<String> iriTemplate()   { return Optional.ofNullable(iriTemplate); }
        public boolean emitProvenance()         { return emitProvenance; }
        public Optional<String> schemaVersion() { return Optional.ofNullable(schemaVersion); }

        /**
         * Predicate IRI &rarr; column name lookup for the rewrite pass.
         * Skips the {@code subject_iri} role (its column carries the
         * subject binding, not a column predicate).
         */
        public Map<String, String> columnsByPredicate() {
            final Map<String, String> out = new LinkedHashMap<>();
            for (Column c : columns) {
                if ("subject_iri".equals(c.role())) continue;
                if (c.predicate() != null) {
                    out.put(c.predicate(), c.name());
                }
            }
            return out;
        }
    }

    /**
     * Anchor block on a {@link RelationalConfig}. Only the
     * {@code class} field is meaningful today (the anchor {@code rdf:type}
     * baked into the generated descriptor); wrapped in a type so future
     * anchor fields (e.g. secondary indexes) have a place to land.
     */
    public static final class Anchor {
        private final String anchorClass;

        public Anchor(final String anchorClass) {
            this.anchorClass = anchorClass;
        }

        /** Absent when the descriptor ships without an {@code anchor.class}. */
        public Optional<String> anchorClass() { return Optional.ofNullable(anchorClass); }
    }

    /**
     * One column in a {@link RelationalConfig}. {@code predicate} is
     * absent for the {@code subject_iri} column; {@code xsdType} is
     * optional.
     */
    public static final class Column {
        private final String name;
        private final String role;
        private final String predicate;
        private final String xsdType;

        public Column(final String name,
                      final String role,
                      final String predicate,
                      final String xsdType) {
            this.name = name;
            this.role = role;
            this.predicate = predicate;
            this.xsdType = xsdType;
        }

        public String name()      { return name; }
        public String role()      { return role; }
        public String predicate() { return predicate; }
        public String xsdType()   { return xsdType; }
    }
}
