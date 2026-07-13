package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Planner-side catalog of federated SPARQL / substrate sources.
 * Consulted by {@link WfFederationRewrite} during the static-mode
 * federation pass (design memo &sect;03 and &sect;04 of
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
 * <p>v0.1 is static-only (memo &sect;11 step 1): predicates are declared
 * in the config file, no ASK probes at plan time. {@code probe_ttl_secs}
 * is parsed and preserved for the v0.2 probe path but ignored by the
 * v0.1 rewrite.
 *
 * <p>Read-only after load; an empty registry (no config flag) is a
 * valid state and is what {@link WfFederationRewrite} treats as an
 * unconditional no-op. Identical lifecycle to {@link FulltextRegistry}
 * and {@link DocumentRegistry}.
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

    private FederationRegistry(final List<FederationSource> sources,
                               final Map<String, Integer> nameToIndex,
                               final Map<String, List<Integer>> predicateToIndices) {
        this.sources = List.copyOf(sources);
        this.nameToIndex = Map.copyOf(nameToIndex);
        // Freeze inner lists so consumers can't mutate the reverse index.
        final Map<String, List<Integer>> frozen = new HashMap<>(predicateToIndices.size());
        for (Map.Entry<String, List<Integer>> e : predicateToIndices.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.predicateToIndices = Map.copyOf(frozen);
    }

    /**
     * Empty registry &mdash; the uninstrumented-startup state. Every
     * lookup is a no-op; {@link #isEmpty()} returns true.
     */
    public static FederationRegistry empty() {
        return new FederationRegistry(List.of(), Map.of(), Map.of());
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
        return new FederationRegistry(list, nameToIndex, predIndex);
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
        return of(parsed);
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

        return new FederationSource(name, type, endpoint, predicates, probeTtl, silent);
    }

    private static SourceType parseType(final String name, final String typeStr) {
        // Accept both hyphenated (memo shape) and underscored spellings
        // for parity with the Rust/RDF4J parsers.
        return switch (typeStr) {
            case "sparql", "SPARQL" -> SourceType.SPARQL;
            case "wf-search", "wf_search", "WfSearch" -> SourceType.WF_SEARCH;
            case "wf-fetch", "wf_fetch", "WfFetch" -> SourceType.WF_FETCH;
            case "wf-document", "wf_document", "WfDocument" -> SourceType.WF_DOCUMENT;
            case "http-sparql", "http_sparql", "HttpSparql" -> SourceType.HTTP_SPARQL;
            default -> throw new IllegalArgumentException(
                    "federation registry source `" + name + "`: unknown type `" + typeStr
                            + "` (expected `sparql`, `wf-search`, `wf-fetch`, `wf-document`, "
                            + "or `http-sparql`)");
        };
    }

    /** Presence-and-non-null helper &mdash; atlas.json has no equivalent of Jackson's {@code hasNonNull}. */
    private static boolean hasNonNull(final JsonObject obj, final String key) {
        if (!obj.hasKey(key)) return false;
        final JsonValue v = obj.get(key);
        return v != null && !v.isNull();
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

        /**
         * Legacy four-field constructor kept for callers that don't need
         * to specify a silent override. Delegates with
         * {@link Optional#empty()} so the rewrite pass falls back to the
         * per-source-type default (memo &sect;08).
         */
        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs) {
            this(name, sourceType, endpoint, predicates, probeTtlSecs, Optional.empty());
        }

        public FederationSource(final String name,
                                final SourceType sourceType,
                                final String endpoint,
                                final List<String> predicates,
                                final OptionalInt probeTtlSecs,
                                final Optional<Boolean> silent) {
            this.name = name;
            this.sourceType = sourceType;
            this.endpoint = endpoint;
            this.predicates = List.copyOf(predicates);
            this.probeTtlSecs = probeTtlSecs;
            this.silent = silent;
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
         * v0.2 ASK-probe cache TTL. {@link OptionalInt#empty()} at v0.1
         * because probe mode isn't wired up; the field is preserved so
         * operators can pre-populate configs for v0.2 without a schema
         * migration.
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
    }
}
