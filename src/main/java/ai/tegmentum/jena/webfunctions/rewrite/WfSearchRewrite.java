package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpTriple;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * URL-sugar rewrite: expand
 * {@code SERVICE <wf-search:name[@time-spec][?opts]>} into a
 * {@code SERVICE <wf-invoke:hex>} allocation with the registry entry's
 * config baked in.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-document-v1.md}
 * &sect;05 (URL grammar) and &sect;10 (implementation notes).
 *
 * <h3>Grammar</h3>
 * <pre>
 *   wf-search:&lt;name&gt;[@&lt;time-spec&gt;][?&lt;opt&gt;=&lt;value&gt;&amp;...]
 *   time-spec ::= ISO-8601-UTC | "rev" &lt;N&gt;
 * </pre>
 *
 * <p>Recognised opt keys: {@code query}, {@code highlight}, {@code lang},
 * {@code filter}, {@code limit}, {@code offset}, {@code include_body},
 * {@code after}, {@code before}. Any other key is silently ignored so
 * operators can drop future keys in without a planner-level upgrade.
 *
 * <p>{@code ?query=<term>} URL-parameter sugar: an alternative to the
 * body-triple form. When the SERVICE body does not carry a
 * {@code wf:query "…"} triple, the folder falls back to the URL opt so
 * cases like {@code SERVICE <wf-search:manuals-search?query=waterproof>
 * { ?_ wf:doc ?m }} still lift to {@code wf-invoke:<hex>}. Body-triple
 * form still wins when both are present (the body is closer to the
 * caller's intent than a URL string decorated by federation config).
 *
 * <p>{@code @time-spec} and the range opts ({@code ?after=} /
 * {@code ?before=}) are mutually exclusive: a URL that combines them is
 * rejected at parse time so the outer rewrite pass leaves the SERVICE
 * untouched (the conservative "unknown SERVICE" fallback). This is the
 * v1.3 range-queries feature — see
 * {@code wf-conformance/docs/design/wf-document-v1.md} &sect;05.
 *
 * <h3>Skip conditions</h3>
 * <ul>
 *   <li>The registered {@code name} isn't in the {@link DocumentRegistry}
 *       (or the registry is empty).</li>
 *   <li>Neither a {@code wf:query "value"} triple in the SERVICE body
 *       nor a {@code ?query=<value>} URL opt is present — the sugar
 *       carries no search string, so there's nothing to invoke.</li>
 * </ul>
 * Both are pass-throughs, not errors — a misconfigured name should
 * surface as a normal SPARQL "no such service" at execution time, not a
 * plan-time crash.
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_search_rewrite.rs} and the
 * RDF4J port at
 * {@code rdf4j-webfunction-plugin/src/main/java/ai/tegmentum/rdf4j/webfunctions/rewrite/WfSearchRewrite.java}.
 */
public final class WfSearchRewrite {

    static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    static final String WF_QUERY = WF_NS + "query";
    /**
     * Guest-emitted column name the SERVICE body binds via
     * {@code ?_ wf:snippet ?var}. Its presence in the collected output
     * projection drives the memo &sect;10 smart-set of
     * {@code highlight: true} on the emitted opts JSON.
     */
    private static final String WF_SNIPPET_COL = "snippet";

    /** The URL scheme this pass recognises at the SERVICE position. */
    public static final String WF_SEARCH_SCHEME = "wf-search:";

    /** Default limit when {@code ?limit=N} is not supplied via the URL. */
    private static final int DEFAULT_LIMIT = 20;

    private final DocumentRegistry registry;
    private final FulltextRegistry fulltextRegistry;
    private final FederationRegistry federationRegistry;
    private final InvokeRegistry invokes;

    public WfSearchRewrite(final DocumentRegistry registry, final InvokeRegistry invokes) {
        this(registry, null, null, invokes);
    }

    public WfSearchRewrite(final DocumentRegistry registry,
                           final FulltextRegistry fulltextRegistry,
                           final InvokeRegistry invokes) {
        this(registry, fulltextRegistry, null, invokes);
    }

    public WfSearchRewrite(final DocumentRegistry registry,
                           final FulltextRegistry fulltextRegistry,
                           final FederationRegistry federationRegistry,
                           final InvokeRegistry invokes) {
        this.registry = registry;
        this.fulltextRegistry = fulltextRegistry;
        this.federationRegistry = federationRegistry;
        this.invokes = invokes;
    }

    /**
     * Static entry point for the pipeline. Returns the input unchanged
     * when all three registries are empty or absent.
     *
     * <p>DocumentRegistry is the primary resolver (wf_document guest ABI).
     * FulltextRegistry is consulted as a fallback so a
     * {@code wf-search:<name>} URL whose target lives only in the
     * fulltext registry (e.g. a federation source aliased through
     * {@code --fulltext-config}) still folds against the wf_fulltext ABI
     * {@code [backend_endpoint, index, query, opts_json]}. Closes the
     * {@code federation_wf_search} conformance case.
     *
     * <p>FederationRegistry is consulted as a third fallback so a
     * {@code wf-search:<name>} URL registered ONLY as a federation
     * source of {@code type = "wf-search"} (no matching fulltext /
     * document entry — the {@code federation_heterogeneous} shape) still
     * folds. The federation entry has no dispatch info of its own, so
     * the pass synthesizes the wf_fulltext-shaped InvokeSpec: backend
     * endpoint from the entry's {@code endpoint} when HTTP-shaped, else
     * {@code $MANTICORE_URL} (fallback {@code http://localhost:9308});
     * wasm URL from {@code $WF_FULLTEXT_WASM_URL} (fallback the
     * well-known convention {@code file:///opt/wf_fulltext.wasm}).
     */
    public static Op rewrite(final Op op,
                             final DocumentRegistry registry,
                             final InvokeRegistry invokes) {
        return rewrite(op, registry, null, null, invokes);
    }

    public static Op rewrite(final Op op,
                             final DocumentRegistry registry,
                             final FulltextRegistry fulltextRegistry,
                             final InvokeRegistry invokes) {
        return rewrite(op, registry, fulltextRegistry, null, invokes);
    }

    public static Op rewrite(final Op op,
                             final DocumentRegistry registry,
                             final FulltextRegistry fulltextRegistry,
                             final FederationRegistry federationRegistry,
                             final InvokeRegistry invokes) {
        if (op == null) return null;
        if (invokes == null) return op;
        if (allRegistriesEmpty(registry, fulltextRegistry, federationRegistry)) return op;
        return new WfSearchRewrite(registry, fulltextRegistry, federationRegistry, invokes)
                .rewrite(op);
    }

    /** Instance entry point. */
    public Op rewrite(final Op op) {
        if (op == null) return null;
        if (invokes == null) return op;
        if (allRegistriesEmpty(registry, fulltextRegistry, federationRegistry)) return op;
        return Transformer.transform(new SearchTransform(), op);
    }

    private static boolean allRegistriesEmpty(final DocumentRegistry registry,
                                              final FulltextRegistry fulltextRegistry,
                                              final FederationRegistry federationRegistry) {
        return (registry == null || registry.isEmpty())
                && (fulltextRegistry == null || fulltextRegistry.isEmpty())
                && (federationRegistry == null || federationRegistry.isEmpty());
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private final class SearchTransform extends TransformCopy {
        @Override
        public Op transform(final OpService opService, final Op subOp) {
            final Node svc = opService.getService();
            if (svc == null || !svc.isURI()) {
                return super.transform(opService, subOp);
            }
            final String uri = svc.getURI();
            if (!uri.startsWith(WF_SEARCH_SCHEME)) {
                return super.transform(opService, subOp);
            }

            final ParsedUrl parsed = parseUrl(uri);
            if (parsed == null) {
                return super.transform(opService, subOp);
            }

            String query = findQueryLiteral(subOp);
            if (query == null) {
                // No wf:query triple in the body -> fall back to the URL
                // opt `?query=<term>` (the URL-parameter sugar form used
                // by federation cases whose SERVICE body doesn't spell
                // out the search string in a triple). If neither is
                // present, there's nothing to invoke on.
                query = parsed.opts.get("query");
                if (query == null || query.isEmpty()) {
                    return super.transform(opService, subOp);
                }
            }

            // Walk the SERVICE body ONCE at rewrite time to derive the
            // (guest_col -> outer_var) projection map from every
            // `?_ wf:<col> ?var` triple. This map has to be captured
            // BEFORE Jena's per-outer-binding substitution rewrites
            // `wf:doc ?m` into `wf:doc <manual_widget>` at execution
            // time — the WfInvokeService fallback that walks the
            // (substituted) sub-op silently misses those, which is what
            // collapses the outer join to a Cartesian product in the
            // federation_wf_search regression. Mirrors the QLever fix
            // (`qlever-wf-runtime::wf_search_rewrite` commit `04fdb03`).
            final Map<String, String> projection = collectOutputProjection(subOp);

            // Memo §10 smart-set flag. `wf:snippet` in the body means
            // the caller expects the snippet cell populated — set
            // `highlight: true` in the emitted opts JSON unless the
            // URL sugar explicitly overrode it. Cheap lookup: the
            // projection map is already keyed on the guest column.
            final boolean projectsSnippet = projection.containsKey(WF_SNIPPET_COL);

            // Primary path: DocumentRegistry (wf_document guest ABI).
            final DocumentRegistry.DocumentIndex docEntry =
                    registry == null ? null : registry.byName(parsed.name);
            if (docEntry != null) {
                final String iri = allocateDocumentInvoke(
                        docEntry, parsed, query, projection, projectsSnippet);
                final Node newSvc = NodeFactory.createURI(iri);
                return new OpService(newSvc, subOp, opService.getSilent());
            }

            // Fallback: FulltextRegistry (wf_fulltext guest ABI). Enables
            // federation sources whose dispatch info lives in
            // --fulltext-config.
            final FulltextRegistry.FulltextIndex ftEntry =
                    fulltextRegistry == null ? null : fulltextRegistry.byName(parsed.name);
            if (ftEntry != null) {
                final String iri = allocateFulltextInvoke(
                        ftEntry, parsed, query, projection, projectsSnippet);
                final Node newSvc = NodeFactory.createURI(iri);
                return new OpService(newSvc, subOp, opService.getSilent());
            }

            // Fallback: FederationRegistry (wf_fulltext guest ABI with
            // synthesized dispatch info). Enables the
            // federation_heterogeneous shape where `manuals-search` is
            // declared ONLY as a federation source of type wf-search
            // with no matching fulltext / document entry.
            final FederationRegistry.FederationSource fedEntry =
                    federationRegistry == null ? null : federationRegistry.byName(parsed.name);
            if (fedEntry != null
                    && fedEntry.sourceType() == FederationRegistry.SourceType.WF_SEARCH) {
                final String iri = allocateFederationWfSearchInvoke(
                        fedEntry, parsed, query, projection, projectsSnippet);
                final Node newSvc = NodeFactory.createURI(iri);
                return new OpService(newSvc, subOp, opService.getSilent());
            }

            // Unregistered on all three -> pass through. Execution-time
            // SERVICE dispatch will raise a proper "no such handler"
            // error rather than us silently substituting a bogus IRI.
            return super.transform(opService, subOp);
        }
    }

    // ---------------------------------------------------------------------
    // URL parsing
    // ---------------------------------------------------------------------

    /** Result of a successful {@code wf-search:} URL parse. */
    static final class ParsedUrl {
        final String name;
        /** ISO-8601 UTC time-spec, or null when absent / when {@link #atRev} is set. */
        final String atTime;
        /** Numeric revision from {@code @rev<N>}, or null when absent / when {@link #atTime} is set. */
        final Long atRev;
        /** Preserved insertion order for stable JSON emission. */
        final Map<String, String> opts;

        ParsedUrl(final String name, final String atTime, final Long atRev,
                  final Map<String, String> opts) {
            this.name = name;
            this.atTime = atTime;
            this.atRev = atRev;
            this.opts = opts;
        }
    }

    /**
     * Handwritten parser: URI class treats {@code wf-search:...} as an
     * opaque URI (no authority component) so it exposes the whole
     * {@code name@time?opts} lump as one string. Splitting by hand is
     * simpler than pretending it's hierarchical.
     */
    static ParsedUrl parseUrl(final String uri) {
        if (!uri.startsWith(WF_SEARCH_SCHEME)) return null;
        String rest = uri.substring(WF_SEARCH_SCHEME.length());
        if (rest.isEmpty()) return null;

        // 1) Split off the opts query-string.
        String optsPart = null;
        final int qIdx = rest.indexOf('?');
        if (qIdx >= 0) {
            optsPart = rest.substring(qIdx + 1);
            rest = rest.substring(0, qIdx);
        }

        // 2) Split off the time-spec.
        String timePart = null;
        final int atIdx = rest.indexOf('@');
        if (atIdx >= 0) {
            timePart = rest.substring(atIdx + 1);
            rest = rest.substring(0, atIdx);
        }

        // 3) What's left is the name. Reject an empty name outright.
        final String name = rest;
        if (name.isEmpty()) return null;

        String atTime = null;
        Long atRev = null;
        if (timePart != null && !timePart.isEmpty()) {
            if (timePart.startsWith("rev")) {
                try {
                    atRev = Long.parseUnsignedLong(timePart.substring(3));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                // Treat as ISO-8601; guest validates the literal shape.
                atTime = timePart;
            }
        }

        final Map<String, String> opts = new LinkedHashMap<>();
        if (optsPart != null && !optsPart.isEmpty()) {
            for (String pair : optsPart.split("&")) {
                if (pair.isEmpty()) continue;
                final int eq = pair.indexOf('=');
                final String key;
                final String value;
                if (eq < 0) {
                    key = urlDecode(pair);
                    value = "";
                } else {
                    key = urlDecode(pair.substring(0, eq));
                    value = urlDecode(pair.substring(eq + 1));
                }
                if (isRecognisedOpt(key)) {
                    opts.put(key, value);
                }
            }
        }

        // v1.3: @time-spec and ?after= / ?before= are mutually exclusive.
        // Reject at parse time so the outer rewrite pass falls through and
        // leaves the SERVICE untouched (the conservative "unknown SERVICE"
        // fallback).
        final boolean hasTimeSpec = atTime != null || atRev != null;
        final boolean hasRangeOpt = opts.containsKey("after") || opts.containsKey("before");
        if (hasTimeSpec && hasRangeOpt) {
            return null;
        }
        return new ParsedUrl(name, atTime, atRev, opts);
    }

    private static boolean isRecognisedOpt(final String key) {
        switch (key) {
            // `query` is the URL-parameter sugar for the body-triple
            // form; the folder consumes it directly and does NOT
            // propagate it into `opts_json` (the guest never receives a
            // `query` opt — the query string is a positional arg).
            case "query":
            case "highlight":
            case "lang":
            case "filter":
            case "limit":
            case "offset":
            case "include_body":
            case "after":
            case "before":
                return true;
            default:
                return false;
        }
    }

    private static String urlDecode(final String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed %-encoding -> pass the raw text through; guest
            // will surface the problem if it matters.
            return s;
        }
    }

    // ---------------------------------------------------------------------
    // SERVICE-body scan for wf:query
    // ---------------------------------------------------------------------

    /**
     * Walk the SERVICE body and collect every {@code ?_ wf:<col> ?var}
     * triple as a (guest_col -> outer_var) rename entry. Java analogue
     * of {@code qlever-wf-runtime::partial_rewrite::collect_output_projection}
     * (commit `04fdb03`).
     *
     * <p>Called at rewrite time so the map is captured BEFORE Jena's
     * per-outer-binding substitution rewrites the still-free {@code ?var}
     * into a bound literal at execution time. The wf-invoke SERVICE
     * dispatcher ({@link ai.tegmentum.jena.webfunctions.WfInvokeService})
     * consumes the map from {@link InvokeRegistry.InvokeSpec#projection}
     * to rename guest-emitted columns onto the outer-query variables the
     * caller declared.
     *
     * <p>Predicates that describe the invocation itself
     * ({@code wf:wasm}, {@code wf:arg}, {@code wf:call}, {@code wf:query})
     * are skipped — they aren't output-column renames. Literal-valued
     * triples are skipped too, for the same reason.
     */
    private static Map<String, String> collectOutputProjection(final Op body) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        OpWalker.walk(body, new OpVisitorBase() {
            void consider(final Triple t) {
                final Node p = t.getPredicate();
                if (p == null || !p.isURI()) return;
                final String pUri = p.getURI();
                if (!pUri.startsWith(WF_NS)) return;
                final String col = pUri.substring(WF_NS.length());
                // Skip config-side predicates the wf:call envelope uses
                // (wasm URL / args / self-call marker) and the
                // wf-search sugar's own query-literal carrier. None of
                // these carry an output-column rename.
                if ("wasm".equals(col) || "arg".equals(col)
                        || "call".equals(col) || "query".equals(col)) {
                    return;
                }
                final Node obj = t.getObject();
                if (obj == null || !obj.isVariable()) return;
                out.put(col, obj.getName());
            }

            @Override
            public void visit(final OpBGP opBGP) {
                for (Triple t : opBGP.getPattern()) consider(t);
            }

            @Override
            public void visit(final OpTriple opTriple) {
                consider(opTriple.getTriple());
            }
        });
        return out;
    }

    /**
     * Walk the SERVICE body looking for a triple whose predicate is
     * {@link #WF_QUERY} and whose object is a literal. Return the
     * lexical form of that literal, or null when no such triple exists.
     */
    private static String findQueryLiteral(final Op body) {
        final String[] found = new String[1];
        OpWalker.walk(body, new OpVisitorBase() {
            void consider(final Triple t) {
                if (found[0] != null) return;
                final Node p = t.getPredicate();
                if (p == null || !p.isURI()) return;
                if (!WF_QUERY.equals(p.getURI())) return;
                final Node o = t.getObject();
                if (o == null || !o.isLiteral()) return;
                found[0] = o.getLiteralLexicalForm();
            }

            @Override
            public void visit(final OpBGP opBGP) {
                for (Triple t : opBGP.getPattern()) consider(t);
            }

            @Override
            public void visit(final OpTriple opTriple) {
                consider(opTriple.getTriple());
            }
        });
        return found[0];
    }

    // ---------------------------------------------------------------------
    // Opts JSON + InvokeSpec allocation
    // ---------------------------------------------------------------------

    /**
     * Allocate an {@code InvokeSpec} for the wf_document guest. Arg
     * positions match the {@code search} export as declared in
     * {@code wf-document.wit} (world {@code document}, v1.3):
     *
     * <ol start="0">
     *   <li>{@code search_backend} — Manticore endpoint from the entry.</li>
     *   <li>{@code storage_backend} — sirix-sql-server endpoint.</li>
     *   <li>{@code index} — Manticore-side table name (entry's
     *       {@code search_index}).</li>
     *   <li>{@code query} — search string from the SERVICE body's
     *       {@code wf:query "…"} triple.</li>
     *   <li>{@code opts_json} — URL opts (highlight, lang, filter,
     *       offset, include_body, limit) merged with
     *       {@code at_time}/{@code at_rev} from the URL time-spec.
     *       {@code limit} lives inside this record per the WIT
     *       ({@code search-opts.limit: option<u32>}); a default is
     *       always emitted by {@link #buildOptsJson}.</li>
     * </ol>
     */
    private String allocateDocumentInvoke(final DocumentRegistry.DocumentIndex entry,
                                          final ParsedUrl parsed,
                                          final String query,
                                          final Map<String, String> projection,
                                          final boolean projectsSnippet) {
        final int limit = resolveLimit(parsed);
        final String optsJson = buildOptsJson(parsed, limit, projectsSnippet);

        final List<Node> args = new ArrayList<>(5);
        args.add(NodeFactory.createLiteralString(entry.searchBackend()));
        args.add(NodeFactory.createLiteralString(entry.storageBackend()));
        args.add(NodeFactory.createLiteralString(entry.searchIndex()));
        args.add(NodeFactory.createLiteralString(query));
        args.add(NodeFactory.createLiteralString(optsJson));

        final long id = invokes.insert(
                new InvokeRegistry.InvokeSpec(entry.guestUrl(), args, "search", projection));
        return InvokeRegistry.iriFor(id);
    }

    /**
     * FulltextRegistry-backed fallback allocator. Emits an InvokeSpec
     * against the wf_fulltext guest ABI:
     * <ol start="0">
     *   <li>{@code backend_endpoint} — HTTP endpoint (Manticore etc.)
     *       from the entry's {@code opts_json.backend_endpoint}; falls
     *       back to {@code http://localhost:9308} when unset.</li>
     *   <li>{@code index} — backend-side name from
     *       {@code opts_json.index}; falls back to the entry's
     *       {@code name}.</li>
     *   <li>{@code query} — search string from the SERVICE body.</li>
     *   <li>{@code opts_json} — URL opts baked in.</li>
     * </ol>
     */
    /**
     * FederationRegistry-backed fallback allocator. Emits an InvokeSpec
     * against the same wf_fulltext guest ABI as
     * {@link #allocateFulltextInvoke}, but the FederationSource carries
     * no dispatch info of its own, so we synthesize:
     *
     * <ul>
     *   <li>{@code backend_endpoint}: {@code entry.endpoint()} when
     *       HTTP-shaped; otherwise {@code $MANTICORE_URL} or the
     *       Manticore default {@code http://localhost:9308}.</li>
     *   <li>{@code index}: {@code entry.name()} — federation entries
     *       don't declare a separate index alias.</li>
     *   <li>wasm URL: {@code $WF_FULLTEXT_WASM_URL} or the well-known
     *       convention {@code file:///opt/wf_fulltext.wasm}. Missing
     *       wasm surfaces at dispatch time as a clear "wasm not found"
     *       error rather than at plan time as the current
     *       "unsupported SERVICE URI" rejection.</li>
     * </ul>
     */
    private String allocateFederationWfSearchInvoke(final FederationRegistry.FederationSource entry,
                                                    final ParsedUrl parsed,
                                                    final String query,
                                                    final Map<String, String> projection,
                                                    final boolean projectsSnippet) {
        final String optsJson = buildFulltextOptsJson(parsed, projectsSnippet);
        final String backendEndpoint = federationBackendEndpoint(entry);
        final String wasmUrl = federationWasmUrl();

        final List<Node> args = new ArrayList<>(4);
        args.add(NodeFactory.createLiteralString(backendEndpoint));
        args.add(NodeFactory.createLiteralString(entry.name()));
        args.add(NodeFactory.createLiteralString(query));
        args.add(NodeFactory.createLiteralString(optsJson));

        final long id = invokes.insert(
                new InvokeRegistry.InvokeSpec(wasmUrl, args, "search", projection));
        return InvokeRegistry.iriFor(id);
    }

    /**
     * Choose the wf_fulltext {@code backend_endpoint} positional arg for
     * a FederationSource. HTTP-shaped entry endpoints pass through
     * verbatim; anything else (typically {@code wf-search:<name>}
     * self-refs) falls through to {@code $MANTICORE_URL} or
     * Manticore's default.
     */
    private static String federationBackendEndpoint(
            final FederationRegistry.FederationSource entry) {
        final String ep = entry.endpoint();
        if (ep != null && (ep.startsWith("http://") || ep.startsWith("https://"))) {
            return ep;
        }
        final String env = System.getenv("MANTICORE_URL");
        if (env != null && !env.isEmpty()) return env;
        return "http://localhost:9308";
    }

    /**
     * Resolve the wf_fulltext.wasm URL for the federation fallback.
     * Prefers the {@code WF_FULLTEXT_WASM_URL} env var (the test
     * harness convention); falls back to the well-known convention
     * path {@code file:///opt/wf_fulltext.wasm} so a runtime dispatch
     * failure has a distinctive, actionable error surface.
     */
    private static String federationWasmUrl() {
        final String env = System.getenv("WF_FULLTEXT_WASM_URL");
        if (env != null && !env.isEmpty()) return env;
        return "file:///opt/wf_fulltext.wasm";
    }

    private String allocateFulltextInvoke(final FulltextRegistry.FulltextIndex entry,
                                          final ParsedUrl parsed,
                                          final String query,
                                          final Map<String, String> projection,
                                          final boolean projectsSnippet) {
        // wf_fulltext guest's WIT `query-opts` record has two REQUIRED
        // fields: `fields: list<string>` and `highlight: bool`. Emit
        // both as defaults; without them the substrate's typed-arg
        // marshaller fails with "record missing required field
        // `fields`".
        final String optsJson = buildFulltextOptsJson(parsed, projectsSnippet);
        final String[] be = splitFulltextOpts(entry);
        final String backendEndpoint = be[0];
        final String indexName = be[1];

        final List<Node> args = new ArrayList<>(4);
        args.add(NodeFactory.createLiteralString(backendEndpoint));
        args.add(NodeFactory.createLiteralString(indexName));
        args.add(NodeFactory.createLiteralString(query));
        args.add(NodeFactory.createLiteralString(optsJson));

        final long id = invokes.insert(
                new InvokeRegistry.InvokeSpec(entry.backendUrl(), args, "search", projection));
        return InvokeRegistry.iriFor(id);
    }

    /**
     * Build JSON matching the wf_fulltext guest's {@code query-opts}
     * WIT record. {@code fields} and {@code highlight} are required.
     *
     * <p>{@code projectsSnippet} implements memo &sect;10: when the
     * SERVICE body projects a variable through {@code wf:snippet}, the
     * substrate defaults {@code highlight} to true so callers don't
     * have to explicitly append {@code ?highlight=true} to the URL.
     * An explicit URL opt still wins — the URL value is checked first
     * and only when unset does the smart-set fall through.
     */
    private static String buildFulltextOptsJson(final ParsedUrl parsed,
                                                final boolean projectsSnippet) {
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"fields\":[]");
        sb.append(",\"highlight\":");
        final String hl = parsed.opts.get("highlight");
        final boolean highlight;
        if (hl == null || hl.isEmpty()) {
            // No URL opt — fall through to memo §10 smart-set.
            highlight = projectsSnippet;
        } else {
            highlight = "true".equalsIgnoreCase(hl) || "1".equals(hl);
        }
        sb.append(highlight ? "true" : "false");
        // Optional fields — propagate whatever the caller supplied.
        appendIntIfPresent(sb, parsed.opts.get("limit"), "limit");
        appendIntIfPresent(sb, parsed.opts.get("offset"), "offset");
        appendStrIfPresent(sb, parsed.opts.get("lang"), "lang");
        appendStrIfPresent(sb, parsed.opts.get("filter"), "filter");
        appendStrIfPresent(sb, parsed.opts.get("after"), "after");
        appendStrIfPresent(sb, parsed.opts.get("before"), "before");
        if (parsed.atTime != null) {
            sb.append(",\"at_time\":\"").append(jsonEscape(parsed.atTime)).append('"');
        }
        if (parsed.atRev != null) {
            sb.append(",\"at_rev\":").append(parsed.atRev.longValue());
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Peel {@code backend_endpoint} and {@code index} from the entry's
     * {@code opts_json}. Returns {@code [backend_endpoint, index]}.
     */
    private static String[] splitFulltextOpts(final FulltextRegistry.FulltextIndex entry) {
        final String opts = entry.optsJson();
        String backendEndpoint = "http://localhost:9308";
        String index = entry.name();
        try {
            final org.apache.jena.atlas.json.JsonValue jv =
                    org.apache.jena.atlas.json.JSON.parseAny(opts);
            if (jv != null && jv.isObject()) {
                final org.apache.jena.atlas.json.JsonObject obj = jv.getAsObject();
                if (obj.hasKey("backend_endpoint") && obj.get("backend_endpoint").isString()) {
                    backendEndpoint = obj.get("backend_endpoint").getAsString().value();
                } else if (obj.hasKey("backend_url") && obj.get("backend_url").isString()) {
                    backendEndpoint = obj.get("backend_url").getAsString().value();
                }
                if (obj.hasKey("index") && obj.get("index").isString()) {
                    index = obj.get("index").getAsString().value();
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed opts_json — fall back to defaults. Registry
            // load-time validation already normalized this to a valid
            // JSON string; a runtime parse failure means someone patched
            // the entry out-of-band, and defaults are safer than crashing
            // the rewrite pass.
        }
        return new String[] {backendEndpoint, index};
    }

    private static int resolveLimit(final ParsedUrl parsed) {
        final String s = parsed.opts.get("limit");
        if (s == null || s.isEmpty()) return DEFAULT_LIMIT;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    /**
     * Query-time opts JSON, hand-built for a stable, testable key order:
     * {@code {"limit":N[,"offset":N][,"highlight":true|false][,"lang":"..."]
     * [,"filter":"..."][,"include_body":true|false][,"after":"..."]
     * [,"before":"..."][,"at_time":"..."][,"at_rev":N]}}.
     *
     * <p>The {@code at_time}/{@code at_rev} bake-in is the whole point of
     * the sugar — the URL's {@code @time-spec} winds up here so the guest
     * doesn't need to re-parse the URL. The v1.3 range fields
     * {@code after}/{@code before} ride in the same slot; they and
     * {@code at_time}/{@code at_rev} are mutually exclusive by the parser
     * (a URL that mixes them fails to parse).
     */
    private static String buildOptsJson(final ParsedUrl parsed, final int limit,
                                        final boolean projectsSnippet) {
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"limit\":").append(limit);
        appendIntIfPresent(sb, parsed.opts.get("offset"), "offset");
        // Memo §10 smart-set: SERVICE body projecting `wf:snippet`
        // defaults `highlight` to true unless the URL opt already
        // says otherwise. `appendBoolIfPresent` short-circuits on a
        // null URL value, so the smart-set only fires when the URL
        // opt is absent.
        appendBoolWithDefault(sb, parsed.opts.get("highlight"), "highlight", projectsSnippet);
        appendStrIfPresent(sb, parsed.opts.get("lang"), "lang");
        appendStrIfPresent(sb, parsed.opts.get("filter"), "filter");
        appendBoolIfPresent(sb, parsed.opts.get("include_body"), "include_body");
        appendStrIfPresent(sb, parsed.opts.get("after"), "after");
        appendStrIfPresent(sb, parsed.opts.get("before"), "before");
        if (parsed.atTime != null) {
            sb.append(",\"at_time\":\"").append(jsonEscape(parsed.atTime)).append('"');
        }
        if (parsed.atRev != null) {
            sb.append(",\"at_rev\":").append(parsed.atRev.longValue());
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Emit a boolean opt with fallback semantics: the URL opt wins when
     * present; otherwise the smart-set default is emitted only when
     * true (memo §10 doesn't want to emit a spurious `highlight:false`
     * on the document path when neither the URL nor the body says
     * anything).
     */
    private static void appendBoolWithDefault(final StringBuilder sb, final String v,
                                              final String key, final boolean smartSet) {
        if (v != null && !v.isEmpty()) {
            final boolean b = "true".equalsIgnoreCase(v) || "1".equals(v);
            sb.append(",\"").append(key).append("\":").append(b);
            return;
        }
        if (smartSet) {
            sb.append(",\"").append(key).append("\":true");
        }
    }

    private static void appendIntIfPresent(final StringBuilder sb, final String v, final String key) {
        if (v == null || v.isEmpty()) return;
        try {
            final long n = Long.parseLong(v);
            sb.append(",\"").append(key).append("\":").append(n);
        } catch (NumberFormatException ignored) {
            // Non-numeric -> drop.
        }
    }

    private static void appendBoolIfPresent(final StringBuilder sb, final String v, final String key) {
        if (v == null || v.isEmpty()) return;
        final boolean b = "true".equalsIgnoreCase(v) || "1".equals(v);
        sb.append(",\"").append(key).append("\":").append(b);
    }

    private static void appendStrIfPresent(final StringBuilder sb, final String v, final String key) {
        if (v == null || v.isEmpty()) return;
        sb.append(",\"").append(key).append("\":\"").append(jsonEscape(v)).append('"');
    }

    private static String jsonEscape(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
