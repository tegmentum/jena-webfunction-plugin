package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.NodeValue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Planner-side BGP rewrite: when a BGP references only column
 * predicates of a single registered shape, all sharing one subject
 * variable, replace the BGP with {@code SERVICE <wf:call>} invoking
 * {@code wf_fetch.wasm} against the shape's sink.
 *
 * <p>Mirrors {@code oxigraph-wf/src/shape_rewrite.rs}. v1 is deliberately
 * conservative:
 *
 * <ul>
 *   <li>Every predicate in the BGP must be a concrete IRI belonging to
 *       one and the same registered shape.</li>
 *   <li>Every subject must be the same variable — mixed subjects, IRI
 *       subjects, or bnode subjects abort the rewrite.</li>
 *   <li>An optional {@code ?s a <class>} triple must match the shape's
 *       anchor class.</li>
 *   <li>Objects must be variables.</li>
 * </ul>
 * BGPs that don't meet the bar pass through unchanged — v1 never
 * splits BGPs into shape-covered and store-covered halves.
 *
 * <h2>Virtual per-shape graph</h2>
 *
 * A shape logically owns a virtual named graph whose IRI is
 * {@code urn:wf:shape:<shape-name>}. When the qualifying BGP is
 * wrapped in {@code GRAPH ?g { ... }} the rewrite folds the graph
 * binding into the emitted algebra:
 *
 * <ul>
 *   <li>{@code GRAPH ?g { shape-BGP }} — the whole {@code OpGraph}
 *       becomes {@code OpExtend(?g = <urn:wf:shape:X>, OpService)}.</li>
 *   <li>{@code GRAPH <urn:wf:shape:X> { shape-BGP }} where the IRI
 *       matches — the {@code OpGraph} wrapper drops; the
 *       {@code OpService} runs against the sink directly.</li>
 *   <li>{@code GRAPH <other-iri> { shape-BGP }} — leave unchanged; the
 *       store's own named-graph iteration handles it (typically 0 rows
 *       since sink triples don't live in any real named graph).</li>
 *   <li>Default-graph shape-BGP — historic path; rewrite to
 *       {@code OpService} with no {@code ?g} binding.</li>
 * </ul>
 *
 * <p>{@code GRAPH} bodies that aren't a bare {@code OpBGP} (nested
 * joins, filters, subqueries) fall through to the default
 * {@code TransformCopy} behaviour so the shape pass never fires in
 * scopes it can't reason about.
 */
public final class ShapeRewrite {

    static final String WF_NS = "http://tegmentum.ai/ns/webfunction/";
    /**
     * Fully-qualified IRI for the {@code SERVICE <wf:call>} dispatch
     * target. Public so {@link ai.tegmentum.jena.webfunctions.WfCallService}
     * can guard against false-matching it as a wasm URL.
     */
    public static final String WF_CALL_IRI = WF_NS + "call";
    static final String WF_WASM_IRI = WF_NS + "wasm";
    static final String WF_ARG_IRI = WF_NS + "arg";
    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    static final String SHAPE_GRAPH_PREFIX = "urn:wf:shape:";

    private ShapeRewrite() {}

    /** Virtual named-graph IRI for a shape. */
    public static String shapeVirtualGraphIri(final String shapeName) {
        return SHAPE_GRAPH_PREFIX + shapeName;
    }

    /**
     * @return rewritten Op — a copy with qualifying BGPs replaced by
     *     SERVICE calls; identity when the registry is empty or the
     *     fetch URL is null.
     */
    public static Op rewrite(final Op op,
                             final ShapeRegistry registry,
                             final String wfFetchUrl) {
        if (registry == null || registry.isEmpty() || wfFetchUrl == null || wfFetchUrl.isEmpty()) {
            return op;
        }
        return Transformer.transform(new ShapeTransform(registry, wfFetchUrl), op);
    }

    private static final class ShapeTransform extends TransformCopy {
        private final ShapeRegistry registry;
        private final String wfFetchUrl;

        ShapeTransform(final ShapeRegistry registry, final String wfFetchUrl) {
            this.registry = registry;
            this.wfFetchUrl = wfFetchUrl;
        }

        @Override
        public Op transform(final OpBGP opBGP) {
            final RewriteResult rewritten = tryRewriteBgp(opBGP.getPattern());
            return rewritten == null ? super.transform(opBGP) : rewritten.op;
        }

        /**
         * Fold shape-BGP rewrites through {@code GRAPH} clauses. See
         * the class docstring for the semantics table.
         *
         * <p>Jena's {@link Transformer} runs bottom-up, so by the time
         * this method fires the inner BGP has already been processed
         * by {@link #transform(OpBGP)} and replaced with an
         * {@link OpService}. Work from {@code opGraph.getSubOp()} (the
         * pristine original) rather than the transformed {@code subOp}
         * so we can decide, from a clean slate, whether the outer
         * GRAPH scope wants a rewritten SERVICE, an unwrapped SERVICE,
         * or the untouched original BGP. Whichever we pick, the
         * already-transformed {@code subOp} is discarded.
         *
         * <p>Only bare-BGP bodies are handled here. Non-BGP bodies —
         * or bodies where the shape rewrite refuses — return the
         * original OpGraph unchanged so the pre-virtual-graph safety
         * net (store semantics under GRAPH) still holds.
         */
        @Override
        public Op transform(final OpGraph opGraph, final Op subOp) {
            final Op original = opGraph.getSubOp();
            if (!(original instanceof OpBGP bgp)) {
                return opGraph;
            }
            final RewriteResult rewritten = tryRewriteBgp(bgp.getPattern());
            if (rewritten == null) {
                return opGraph;
            }
            final Node name = opGraph.getNode();
            final String virt = shapeVirtualGraphIri(rewritten.shapeName);
            if (name.isVariable()) {
                final Var graphVar = Var.alloc(name.getName());
                return OpExtend.create(rewritten.op, graphVar,
                        NodeValue.makeNode(NodeFactory.createURI(virt)));
            }
            if (name.isURI()) {
                if (virt.equals(name.getURI())) {
                    return rewritten.op;
                }
                // Named IRI that isn't the shape's virtual — leave
                // GRAPH alone. The store's own scan produces zero rows
                // against a non-existent named graph, matching SPARQL
                // 1.1 semantics.
                return opGraph;
            }
            return opGraph;
        }

        private RewriteResult tryRewriteBgp(final BasicPattern pattern) {
            if (pattern.isEmpty()) {
                return null;
            }

            // Extract shared subject variable — mixed subjects abort.
            final Var subjectVar = singleSubjectVariable(pattern);
            if (subjectVar == null) {
                return null;
            }

            String classIri = null;
            final List<Map.Entry<String, Var>> columns = new ArrayList<>(pattern.size());
            for (Triple t : pattern) {
                final Node p = t.getPredicate();
                if (!p.isURI()) {
                    return null;
                }
                final String predIri = p.getURI();
                if (RDF_TYPE.equals(predIri)) {
                    final Node obj = t.getObject();
                    if (!obj.isURI()) {
                        return null;
                    }
                    classIri = obj.getURI();
                    continue;
                }
                final Node obj = t.getObject();
                if (!obj.isVariable()) {
                    return null;
                }
                columns.add(new AbstractMap.SimpleImmutableEntry<>(
                        predIri, Var.alloc(obj.getName())));
            }

            final String finalClassIri = classIri;
            return resolveShape(columns, finalClassIri)
                    .map(pair -> {
                        // Bare `?s a :Widget` case: no column triples,
                        // but the anchor-class match alone is enough
                        // to dispatch (see graph_shape_virtual.toml).
                        if (pair.getValue().isEmpty()
                                && pair.getKey().anchorClass == null) {
                            return null;
                        }
                        final Op service = buildService(
                                subjectVar, pair.getKey(), pair.getValue());
                        return new RewriteResult(service, pair.getKey().name);
                    })
                    .orElse(null);
        }

        /**
         * All triples must share the same subject variable. Returns
         * that variable, or {@code null} if the pattern doesn't
         * qualify.
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

        private java.util.Optional<Map.Entry<ShapeRegistry.ShapeEntry, List<Map.Entry<String, Var>>>>
        resolveShape(final List<Map.Entry<String, Var>> predicatesAndVars,
                     final String classIri) {
            ShapeRegistry.ShapeEntry shape = null;
            for (Map.Entry<String, Var> e : predicatesAndVars) {
                final java.util.Optional<ShapeRegistry.ShapeEntry> maybe =
                        registry.findByPredicate(e.getKey());
                if (maybe.isEmpty()) {
                    return java.util.Optional.empty();
                }
                if (shape == null) {
                    shape = maybe.get();
                } else if (shape != maybe.get()) {
                    return java.util.Optional.empty();
                }
            }
            // Anchor-only fallback: bare `?s a :Widget` pattern picks
            // the shape whose anchor_class matches, even without any
            // column predicates.
            if (shape == null && classIri != null) {
                shape = registry.findByClass(classIri).orElse(null);
            }
            if (shape == null) {
                return java.util.Optional.empty();
            }
            if (classIri != null && !classIri.equals(shape.anchorClass)) {
                return java.util.Optional.empty();
            }
            final List<Map.Entry<String, Var>> mapped = new ArrayList<>(predicatesAndVars.size());
            for (Map.Entry<String, Var> e : predicatesAndVars) {
                final String col = shape.columnsByPredicate.get(e.getKey());
                if (col == null) {
                    return java.util.Optional.empty();
                }
                mapped.add(new AbstractMap.SimpleImmutableEntry<>(col, e.getValue()));
            }
            return java.util.Optional.of(new AbstractMap.SimpleImmutableEntry<>(shape, mapped));
        }

        /**
         * Construct the SERVICE-envelope Op for {@code wf_fetch}. Shape:
         *
         * <pre>
         * SERVICE &lt;wf:call&gt; {
         *   _:c wf:wasm &lt;wf_fetch_url&gt; ;
         *       wf:arg  "&lt;descriptor-json&gt;" .
         *   _:o wf:&lt;subject_iri_column&gt; ?s ;
         *       wf:&lt;col1&gt; ?var1 ;
         *       wf:&lt;col2&gt; ?var2 .
         * }
         * </pre>
         */
        private Op buildService(final Var subjectVar,
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

    /** Rewrite output pair: the SERVICE op and the shape's name for
     *  virtual-graph IRI construction. */
    private record RewriteResult(Op op, String shapeName) {}
}
