package ai.tegmentum.jena.webfunctions.rewrite;

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

    private ShapeRewrite() {}

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
            final Op rewritten = tryRewriteBgp(opBGP.getPattern());
            return rewritten == null ? super.transform(opBGP) : rewritten;
        }

        private Op tryRewriteBgp(final BasicPattern pattern) {
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
            if (columns.isEmpty()) {
                return null;
            }

            return resolveShape(columns, classIri)
                    .map(pair -> buildService(subjectVar, pair.getKey(), pair.getValue()))
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
}
