package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * URL-sugar rewrite: fold
 * {@code SERVICE <wf-fetch:<name>>} clauses (produced upstream by
 * {@link WfFederationRewrite} for FederationSources of type
 * {@link SourceType#WF_FETCH}) into the same
 * {@code SERVICE <wf:call>} envelope {@link ShapeRewrite} already
 * emits for direct-BGP shape hits.
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-federation.md}
 * &sect;06 (heterogeneous sources; the federation rewrite synthesizes
 * {@code wf-fetch:<name>}; a downstream fold pass turns that into the
 * actual wf_fetch.wasm dispatch).
 *
 * <h3>Wire shape</h3>
 * <pre>
 * Before:
 * SERVICE &lt;wf-fetch:widget_fetch&gt; {
 *   ?w :sku ?sku ; :price ?price ; :stock ?stock
 * }
 *
 * After:
 * SERVICE &lt;wf:call&gt; {
 *   _:c wf:wasm &lt;file:///.../wf_fetch.wasm&gt; ;
 *       wf:arg  "&lt;descriptor-json&gt;" .
 *   _:o wf:id     ?w ;
 *       wf:sku    ?sku ;
 *       wf:price  ?price ;
 *       wf:stock  ?stock .
 * }
 * </pre>
 *
 * <h3>Option A: shape-registry bridge, keyed by name</h3>
 * The name after {@code wf-fetch:} is looked up in both the
 * {@link FederationRegistry} (to confirm the source is
 * {@link SourceType#WF_FETCH}) and the {@link ShapeRegistry} (to get the
 * descriptor JSON, sink URL, column list). This is the "shortest closure"
 * &mdash; the shape registry already carries the wf_fetch wire contract;
 * the federation registry just names which source to route to. The
 * operator declares both under the same name.
 *
 * <h3>Skip conditions</h3>
 * <ul>
 *   <li>{@link ShapeRegistry} empty or {@code wfFetchUrl} null/empty
 *       &mdash; short-circuit.</li>
 *   <li>Unknown name &mdash; leave the SERVICE alone (users may operate
 *       their own {@code wf-fetch:} handlers).</li>
 *   <li>Body isn't a plain BGP over the shape's declared predicates all
 *       sharing a single subject variable &mdash; leave alone.</li>
 * </ul>
 *
 * <p>Java sibling of {@code oxigraph-wf/src/wf_fetch_rewrite.rs}.
 */
public final class WfFetchRewrite {

    /** The URL scheme this pass recognises at the SERVICE position. */
    public static final String WF_FETCH_SCHEME = "wf-fetch:";

    private static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    private static final String WF_CALL_IRI = WF_NS + "call";
    private static final String WF_WASM_IRI = WF_NS + "wasm";
    private static final String WF_ARG_IRI  = WF_NS + "arg";
    private static final String RDF_TYPE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private WfFetchRewrite() {}

    /**
     * @return rewritten Op &mdash; a copy with qualifying
     *     {@code SERVICE <wf-fetch:...>} clauses replaced by
     *     {@code SERVICE <wf:call>} envelopes; identity when either
     *     registry is empty or the fetch URL is null.
     */
    public static Op rewrite(final Op op,
                             final FederationRegistry federationRegistry,
                             final ShapeRegistry shapeRegistry,
                             final String wfFetchUrl) {
        if (op == null) return null;
        if (shapeRegistry == null || shapeRegistry.isEmpty()) return op;
        if (wfFetchUrl == null || wfFetchUrl.isEmpty()) return op;
        return Transformer.transform(
                new FetchTransform(federationRegistry, shapeRegistry, wfFetchUrl), op);
    }

    // ---------------------------------------------------------------------
    // Transform
    // ---------------------------------------------------------------------

    private static final class FetchTransform extends TransformCopy {
        private final FederationRegistry federationRegistry;
        private final ShapeRegistry shapeRegistry;
        private final String wfFetchUrl;

        FetchTransform(final FederationRegistry federationRegistry,
                       final ShapeRegistry shapeRegistry,
                       final String wfFetchUrl) {
            this.federationRegistry = federationRegistry;
            this.shapeRegistry = shapeRegistry;
            this.wfFetchUrl = wfFetchUrl;
        }

        @Override
        public Op transform(final OpService opService, final Op subOp) {
            final Node svc = opService.getService();
            if (svc == null || !svc.isURI()) {
                return super.transform(opService, subOp);
            }
            final String uri = svc.getURI();
            if (!uri.startsWith(WF_FETCH_SCHEME)) {
                return super.transform(opService, subOp);
            }
            final String name = uri.substring(WF_FETCH_SCHEME.length());
            if (name.isEmpty()) {
                return super.transform(opService, subOp);
            }
            // Defensive federation-registry check: if the federation
            // registry has this name at all, it must be a WF_FETCH source.
            // Absent from the registry is still allowed &mdash; users may
            // reach this pass via an explicit SERVICE clause.
            if (federationRegistry != null) {
                final FederationSource fs = federationRegistry.byName(name);
                if (fs != null && fs.sourceType() != SourceType.WF_FETCH) {
                    return super.transform(opService, subOp);
                }
            }
            final Optional<ShapeRegistry.ShapeEntry> maybeShape =
                    shapeRegistry.shapeByName(name);
            if (maybeShape.isEmpty()) {
                return super.transform(opService, subOp);
            }
            final ShapeRegistry.ShapeEntry shape = maybeShape.get();

            // Extract BGP triples from the SERVICE body.
            final BasicPattern bgp = collectBgp(subOp);
            if (bgp == null || bgp.isEmpty()) {
                return super.transform(opService, subOp);
            }
            final Var subjectVar = singleSubjectVariable(bgp);
            if (subjectVar == null) {
                return super.transform(opService, subOp);
            }

            final List<Map.Entry<String, Var>> columns =
                    new ArrayList<>(bgp.size());
            for (Triple t : bgp) {
                final Node p = t.getPredicate();
                if (!p.isURI()) {
                    return super.transform(opService, subOp);
                }
                final String predIri = p.getURI();
                // rdf:type triples are structural &mdash; the anchor
                // class is already baked into the shape entry.
                if (RDF_TYPE.equals(predIri)) {
                    continue;
                }
                final Node obj = t.getObject();
                if (!obj.isVariable()) {
                    return super.transform(opService, subOp);
                }
                final String col = shape.columnsByPredicate.get(predIri);
                if (col == null) {
                    return super.transform(opService, subOp);
                }
                columns.add(new AbstractMap.SimpleImmutableEntry<>(
                        col, Var.alloc(obj.getName())));
            }
            if (columns.isEmpty()) {
                return super.transform(opService, subOp);
            }
            return buildWfCallService(subjectVar, shape, columns);
        }

        /**
         * The federation-emitted body is always an {@link OpBGP}; be
         * tolerant to a couple of trivial wrappers just in case. Returns
         * {@code null} if the sub-op isn't a plain BGP shape.
         */
        private static BasicPattern collectBgp(final Op op) {
            if (op instanceof OpBGP bgp) {
                return bgp.getPattern();
            }
            return null;
        }

        /**
         * All triples must share the same subject variable. Same rule as
         * {@link ShapeRewrite}'s private helper.
         */
        private static Var singleSubjectVariable(final BasicPattern pattern) {
            Var chosen = null;
            for (Triple t : pattern) {
                final Node s = t.getSubject();
                if (!s.isVariable()) {
                    return null;
                }
                final Var v = Var.alloc(s.getName());
                if (chosen == null) {
                    chosen = v;
                } else if (!chosen.equals(v)) {
                    return null;
                }
            }
            return chosen;
        }

        /**
         * Construct the same SERVICE-envelope Op that {@link ShapeRewrite}
         * emits for a direct-BGP shape hit. Logic mirrored here rather
         * than shared to keep {@code ShapeRewrite}'s private helper
         * private &mdash; the two passes match disjoint SERVICE shapes.
         */
        private Op buildWfCallService(final Var subjectVar,
                                      final ShapeRegistry.ShapeEntry shape,
                                      final List<Map.Entry<String, Var>> columns) {
            final Node cnode = NodeFactory.createBlankNode();
            final Node onode = NodeFactory.createBlankNode();

            final BasicPattern bp = new BasicPattern();
            // Config side (cnode): the wasm URL and descriptor JSON literal.
            bp.add(Triple.create(cnode,
                    NodeFactory.createURI(WF_WASM_IRI),
                    NodeFactory.createURI(wfFetchUrl)));
            bp.add(Triple.create(cnode,
                    NodeFactory.createURI(WF_ARG_IRI),
                    NodeFactory.createLiteralString(shape.descriptorJson)));

            // Output side (onode).
            bp.add(Triple.create(onode,
                    NodeFactory.createURI(WF_NS + shape.subjectColumnName),
                    subjectVar));
            for (Map.Entry<String, Var> col : columns) {
                bp.add(Triple.create(onode,
                        NodeFactory.createURI(WF_NS + col.getKey()),
                        col.getValue()));
            }

            return new OpService(NodeFactory.createURI(WF_CALL_IRI),
                    new OpBGP(bp), false);
        }
    }
}
