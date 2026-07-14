package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpService;

/**
 * Federation-dispatch rewrite for {@code wf-vector:} URL sugar.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-vector.md}
 * &sect;07.1 and &sect;10.
 *
 * <h3>Purpose</h3>
 *
 * <p>Oxigraph folds {@code SERVICE <wf-vector:<name>?query=…&k=…>} into
 * a {@code VALUES} block by calling its embedded {@code VectorIndex}
 * directly (see {@code oxigraph-wf/src/wf_vector_rewrite.rs}). Jena has
 * no native vector index in v0.1 (memo &sect;10 &mdash; "Vector index
 * on Jena / RDF4J / QLever &hellip; v0.2+").
 *
 * <p>Rather than porting {@code instant-distance} into the plugin, v0.2
 * federates the KNN dispatch to a remote Oxigraph acting as a
 * vector-only source (memo &sect;07.1). This pass rewrites
 *
 * <pre>
 * SERVICE &lt;wf-vector:products?query=%5B1,0,0,0%5D&amp;k=3&gt; {
 *   ?_ wf:doc ?doc ; wf:score ?score
 * }
 * </pre>
 *
 * into
 *
 * <pre>
 * SERVICE &lt;http://oxigraph-vector:PORT/query&gt; {
 *   SERVICE &lt;wf-vector:products?query=%5B1,0,0,0%5D&amp;k=3&gt; {
 *     ?_ wf:doc ?doc ; wf:score ?score
 *   }
 * }
 * </pre>
 *
 * <p>Jena's own SERVICE dispatcher POSTs the inner content to the
 * remote Oxigraph, whose {@code wf_vector_rewrite} pass folds the inner
 * SERVICE to a VALUES block via KNN and returns the bindings.
 *
 * <h3>Registry lookup</h3>
 *
 * <p>The {@code <name>} after {@code wf-vector:} is looked up in
 * {@link FederationRegistry}. A hit is only considered when:
 *
 * <ul>
 *   <li>The source's {@link FederationSource#sourceType()} is
 *       {@link SourceType#WF_VECTOR}.</li>
 *   <li>The source's {@link FederationSource#endpoint()} is an
 *       {@code http://} or {@code https://} URL &mdash; the outer
 *       SERVICE has to dispatch somewhere reachable.</li>
 * </ul>
 *
 * <p>Anything else (unknown name, non-HTTP endpoint, wrong source type)
 * leaves the SERVICE alone. The engine's dispatcher will raise an
 * "unsupported SERVICE URI" error at execution time &mdash; that's the
 * memo &sect;10 honest fallback and the operator sees a clear failure
 * surface rather than a silent no-op.
 *
 * <h3>Pipeline position</h3>
 *
 * <p>Runs BEFORE {@link WfFederationRewrite} so the outer HTTP-SERVICE
 * wrap this pass synthesises never becomes a candidate for BGP
 * federation. Runs AFTER alias canonicalisation so the emitted HTTP
 * endpoint URL matches whatever the case authored.
 *
 * <p>Java sibling of {@code qlever-wf-runtime/src/wf_vector_rewrite.rs}
 * and the RDF4J port at
 * {@code rdf4j-webfunction-plugin/src/main/java/ai/tegmentum/rdf4j/webfunctions/rewrite/WfVectorRewrite.java}.
 */
public final class WfVectorRewrite {

    /** The URL scheme this pass recognises at the SERVICE position. */
    public static final String WF_VECTOR_SCHEME = "wf-vector:";

    private final FederationRegistry registry;

    public WfVectorRewrite(final FederationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Static entry point for the pipeline. Returns the input unchanged
     * when the federation registry is empty or absent.
     */
    public static Op rewrite(final Op op, final FederationRegistry registry) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty()) {
            return op;
        }
        return new WfVectorRewrite(registry).rewrite(op);
    }

    /** Instance entry point. */
    public Op rewrite(final Op op) {
        if (op == null) return null;
        if (registry == null || registry.isEmpty()) return op;
        return Transformer.transform(new VectorTransform(), op);
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private final class VectorTransform extends TransformCopy {
        @Override
        public Op transform(final OpService opService, final Op subOp) {
            final Node svc = opService.getService();
            if (svc == null || !svc.isURI()) {
                return super.transform(opService, subOp);
            }
            final String uri = svc.getURI();
            if (!uri.startsWith(WF_VECTOR_SCHEME)) {
                return super.transform(opService, subOp);
            }
            final String vectorName = parseVectorName(uri);
            if (vectorName == null) {
                return super.transform(opService, subOp);
            }
            final FederationSource source = registry.byName(vectorName);
            if (source == null) {
                return super.transform(opService, subOp);
            }
            if (source.sourceType() != SourceType.WF_VECTOR) {
                return super.transform(opService, subOp);
            }
            if (!isHttpUrl(source.endpoint())) {
                return super.transform(opService, subOp);
            }

            // Build the inner SERVICE preserving the caller's original
            // URI + SILENT semantics. subOp is the transformed body —
            // handing it to the OpService constructor keeps the algebra
            // consistent with the bottom-up transform contract.
            final OpService innerService = new OpService(
                    opService.getService(), subOp, opService.getSilent());
            // Wrap in outer SERVICE targeting the registered HTTP
            // endpoint. Preserve SILENT — the caller's intent about
            // KNN failure propagation is what matters, and the outer
            // wrap is transparent to that decision.
            return new OpService(
                    NodeFactory.createURI(source.endpoint()),
                    innerService,
                    opService.getSilent());
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Extract the registered index name from a
     * {@code wf-vector:<name>[?…]} URL. Everything up to the first
     * {@code ?} (or end of string) is the name; query-string opts are
     * preserved verbatim in the inner SERVICE that the remote Oxigraph
     * consumes.
     */
    static String parseVectorName(final String iri) {
        final String rest = iri.substring(WF_VECTOR_SCHEME.length());
        if (rest.isEmpty()) return null;
        final int q = rest.indexOf('?');
        final String name = (q >= 0) ? rest.substring(0, q) : rest;
        return name.isEmpty() ? null : name;
    }

    /**
     * True when {@code url} is dispatchable via SPARQL's
     * {@code SERVICE <http…>}. Non-HTTP endpoints (an authoring mistake,
     * or a wf-vector source registered as {@code wf-vector:name} sugar
     * itself) are left alone so the failure surface stays visible.
     */
    static boolean isHttpUrl(final String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }
}
