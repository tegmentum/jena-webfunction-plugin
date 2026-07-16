package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.sparql.algebra.Op;

/**
 * Orchestrates the webfunction query-rewrite passes.
 *
 * <p>Runs the passes in the same order as
 * {@code oxigraph-wf/src/main.rs} (lines 630–665 of that file):
 *
 * <ol>
 *   <li>{@link PartialRewrite} — constant-fold {@code wf:partial(...)}
 *       so downstream passes see the folded {@code wf-invoke:} IRIs
 *       instead of the FunctionCall expression.</li>
 *   <li>{@link ConversionRewrite} — expand virtual
 *       {@code urn:wf:conversion:*} named graphs into computed
 *       triples.</li>
 *   <li>{@link AliasRewrite} — substitute IRI aliases with their
 *       canonical form and record the reverse map for the solution
 *       serializer.</li>
 *   <li>{@link WfFederationRewrite} — assign BGP triples to
 *       registered federated sources; emits {@code SERVICE} clauses
 *       (raw endpoint for SPARQL sources, substrate URL sugar for
 *       {@code wf-*} sources). Runs before wf-search so the
 *       synthesised {@code wf-search:} URIs are visible to it.</li>
 *   <li>{@link WfSearchRewrite} — expand
 *       {@code SERVICE <wf-search:name[@time][?opts]>} into a
 *       {@code wf-invoke:} allocation with the registered document
 *       index's config baked in.</li>
 *   <li>{@link FulltextRewrite} — lift FILTER over indexed predicates
 *       into a {@code SERVICE <wf-invoke:>} dispatch.</li>
 *   <li>{@link ShapeRewrite} — replace shape-covered BGPs with
 *       {@code SERVICE <wf:call>} against {@code wf_fetch.wasm}.</li>
 * </ol>
 *
 * <p>Any registry that is empty (or a null fetch URL for
 * ShapeRewrite) short-circuits its pass to identity — a plugin
 * configured with no shapes/conversions/aliases pays effectively zero
 * cost.
 */
public final class RewritePipeline {

    /** Bag of side-inputs and side-outputs for a single rewrite. */
    public static final class Context {
        public final InvokeRegistry invokeRegistry;
        public final ConversionRegistry conversionRegistry;
        public final AliasMap aliasMap;
        public final ShapeRegistry shapeRegistry;
        public final FulltextRegistry fulltextRegistry;
        public final DocumentRegistry documentRegistry;
        public final FederationRegistry federationRegistry;
        public final String wfFetchUrl;

        public Context(final InvokeRegistry invokeRegistry,
                       final ConversionRegistry conversionRegistry,
                       final AliasMap aliasMap,
                       final ShapeRegistry shapeRegistry,
                       final String wfFetchUrl) {
            this(invokeRegistry, conversionRegistry, aliasMap, shapeRegistry,
                    null, null, null, wfFetchUrl);
        }

        public Context(final InvokeRegistry invokeRegistry,
                       final ConversionRegistry conversionRegistry,
                       final AliasMap aliasMap,
                       final ShapeRegistry shapeRegistry,
                       final FulltextRegistry fulltextRegistry,
                       final String wfFetchUrl) {
            this(invokeRegistry, conversionRegistry, aliasMap, shapeRegistry,
                    fulltextRegistry, null, null, wfFetchUrl);
        }

        public Context(final InvokeRegistry invokeRegistry,
                       final ConversionRegistry conversionRegistry,
                       final AliasMap aliasMap,
                       final ShapeRegistry shapeRegistry,
                       final FulltextRegistry fulltextRegistry,
                       final DocumentRegistry documentRegistry,
                       final String wfFetchUrl) {
            this(invokeRegistry, conversionRegistry, aliasMap, shapeRegistry,
                    fulltextRegistry, documentRegistry, null, wfFetchUrl);
        }

        public Context(final InvokeRegistry invokeRegistry,
                       final ConversionRegistry conversionRegistry,
                       final AliasMap aliasMap,
                       final ShapeRegistry shapeRegistry,
                       final FulltextRegistry fulltextRegistry,
                       final DocumentRegistry documentRegistry,
                       final FederationRegistry federationRegistry,
                       final String wfFetchUrl) {
            this.invokeRegistry = invokeRegistry;
            this.conversionRegistry = conversionRegistry;
            this.aliasMap = aliasMap;
            this.shapeRegistry = shapeRegistry;
            this.fulltextRegistry = fulltextRegistry;
            this.documentRegistry = documentRegistry;
            this.federationRegistry = federationRegistry;
            this.wfFetchUrl = wfFetchUrl;
        }
    }

    public static final class Result {
        public final Op rewrittenOp;
        public final AliasRewriteState aliasState;

        Result(final Op rewrittenOp, final AliasRewriteState aliasState) {
            this.rewrittenOp = rewrittenOp;
            this.aliasState = aliasState;
        }
    }

    private RewritePipeline() {}

    /**
     * Resolve the wf_sagegraph.wasm URL from the {@code WF_SAGEGRAPH_WASM_URL}
     * env var. Returns empty string when unset so
     * {@link WfSageGraphRewrite#rewrite(Op, InvokeRegistry, String)}
     * short-circuits — operators without the guest artifact see the
     * sugar reach their SERVICE dispatcher and surface a normal
     * unresolved-SERVICE error rather than a silent no-op fold.
     * Mirrors {@code oxigraph-wf/src/main.rs} line 1263-1264.
     */
    private static String sagegraphWasmUrl() {
        final String env = System.getenv("WF_SAGEGRAPH_WASM_URL");
        return env == null ? "" : env;
    }

    public static Result run(final Op op, final Context ctx) {
        Op cursor = op;
        // 1. wf:partial fold — constant expressions become opaque IRIs.
        cursor = PartialRewrite.rewrite(cursor, ctx.invokeRegistry);
        // 2. Conversion rewrite — virtual conversion graphs become BGPs.
        cursor = ConversionRewrite.rewrite(cursor, ctx.conversionRegistry);
        // 3. Alias rewrite — alias → canonical everywhere.
        final AliasRewrite.Result aliasRes = AliasRewrite.rewrite(cursor, ctx.aliasMap);
        cursor = aliasRes.rewrittenOp;
        // 3b. wf-vector federation dispatch (wf-vector memo §07.1 / §10):
        //     wrap SERVICE <wf-vector:<name>?…> in an outer
        //     SERVICE <http-endpoint> when the FederationRegistry has a
        //     matching WF_VECTOR source with an HTTP endpoint. Runs
        //     BEFORE WfFederationRewrite so the synthesised outer
        //     HTTP-SERVICE never becomes a candidate for BGP
        //     federation. Empty registry short-circuits.
        cursor = WfVectorRewrite.rewrite(cursor, ctx.federationRegistry);
        // 3c. wf-sagegraph guest-dispatch (wf-sagegraph memo §04 / §11):
        //     fold SERVICE <wf-sagegraph:<name>?node=<uri>&k=N> into a
        //     SERVICE <wf-invoke:<hex>> allocation targeting the
        //     locally-registered wf_sagegraph guest wasm at
        //     $WF_SAGEGRAPH_WASM_URL. Runs BEFORE WfFederationRewrite /
        //     WfSearchRewrite / ShapeRewrite so downstream passes see
        //     the folded shape rather than an opaque wf-sagegraph:
        //     SERVICE they would leave alone. Unlike wf-vector, this
        //     dispatches the guest LOCALLY (each engine registers the
        //     wf:sagegraph/host@0.1.0 callback per wave-8); no
        //     remote-Oxigraph federation involved. Unset wasm URL or
        //     absent SERVICE-body wf:embedding projection →
        //     short-circuits inside the pass.
        cursor = WfSageGraphRewrite.rewrite(cursor, ctx.invokeRegistry,
                sagegraphWasmUrl());
        // 4. Federation rewrite — assign registered BGP triples to their
        //    federated source and emit SERVICE clauses. Runs before
        //    wf-search so the synthesised `wf-search:` URIs are visible
        //    to that pass; a `wf-search:`-typed source produces the same
        //    URL sugar a user would have written by hand.
        cursor = WfFederationRewrite.rewrite(cursor, ctx.federationRegistry, ctx.invokeRegistry);
        // 5. wf-search URL sugar — expand SERVICE <wf-search:name...> into
        //    a SERVICE <wf-invoke:> allocation with registry config baked
        //    in (wf-document-v1.md §05). Runs after Alias so the SERVICE
        //    URI is already canonical, and before Fulltext/Shape so the
        //    subsequent passes see the substituted invoke IRI as opaque.
        // WfSearchRewrite consults BOTH DocumentRegistry (primary,
        // wf_document guest ABI) and FulltextRegistry (fallback,
        // wf_fulltext guest ABI); additionally, FederationRegistry is a
        // third fallback so `wf-search:<name>` URLs registered ONLY as a
        // federation source of type wf-search (the
        // federation_heterogeneous shape) still fold.
        cursor = WfSearchRewrite.rewrite(cursor, ctx.documentRegistry,
                ctx.fulltextRegistry, ctx.federationRegistry, ctx.invokeRegistry);
        // 5. Fulltext filter-fold — lift FILTER over indexed predicates
        //    into a SERVICE <wf-invoke:> dispatch (memo §06). Runs after
        //    Alias so we see canonical predicate IRIs, and before Shape so
        //    shape-covered BGPs still take precedence for BGP rewriting.
        cursor = FulltextRewrite.rewrite(cursor, ctx.fulltextRegistry, ctx.invokeRegistry);
        // 6a. wf-fetch URL sugar — fold SERVICE <wf-fetch:name> emitted
        //     by WfFederationRewrite into the SERVICE <wf:call> envelope
        //     shape_rewrite emits for direct-BGP shape hits. Bridges
        //     FederationRegistry (names the source, confirms WF_FETCH
        //     type) to ShapeRegistry (supplies the wire contract) via
        //     same-name lookup. Empty registries or null wfFetchUrl →
        //     short-circuit.
        cursor = WfFetchRewrite.rewrite(cursor, ctx.federationRegistry,
                ctx.shapeRegistry, ctx.wfFetchUrl);
        // 6a1. wf-relational URL sugar — fold SERVICE <wf-relational:name>
        //      emitted by WfFederationRewrite into the same
        //      SERVICE <wf:call> envelope, with the Postgres sink URL
        //      + shape descriptor baked into the descriptor literal
        //      (wf-relational memo §04). v0.3 reads the shape descriptor
        //      straight off the FederationSource.relationalConfig field
        //      (folded in from the old WfRelationalRegistry sidecar).
        //      Empty federation registry or null wfFetchUrl →
        //      short-circuit; unknown name → leave the SERVICE alone;
        //      wrong source type or missing relationalConfig block →
        //      leave alone.
        cursor = WfRelationalRewrite.rewrite(cursor, ctx.federationRegistry,
                ctx.wfFetchUrl);
        // 6b. Shape rewrite — cover BGPs with SERVICE <wf:call>.
        cursor = ShapeRewrite.rewrite(cursor, ctx.shapeRegistry, ctx.wfFetchUrl);
        return new Result(cursor, aliasRes.state);
    }
}
