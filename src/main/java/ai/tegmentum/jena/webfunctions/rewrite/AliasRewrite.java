package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.graph.NodeTransform;
import org.apache.jena.sparql.graph.NodeTransformLib;

import java.util.HashMap;
import java.util.Map;

/**
 * IRI aliasing pass: on the input path, substitute every concrete IRI
 * matched by the {@link AliasMap} with its canonical form and record
 * the (canonical &rarr; original alias) pairing in an
 * {@link AliasRewriteState} for the solution serializer to consult on
 * the output path.
 *
 * <p>Mirrors {@code oxigraph-wf/src/alias_rewrite.rs::rewrite_query}. We
 * lean on Jena's {@link NodeTransformLib} to walk every Node reference
 * in the algebra (BGPs, quads, GRAPH names, SERVICE names, expressions,
 * VALUES tables), so nothing is missed compared to spargebra's manual
 * pattern walker.
 *
 * <p>Only IRIs are candidates. Literals, blank nodes, and variables
 * pass through unchanged. Bare canonicals that the caller didn't
 * mention are not affected on the output path — the reverse map only
 * records the aliases the caller actually typed.
 */
public final class AliasRewrite {

    private AliasRewrite() {}

    /** Result of one rewrite pass. */
    public static final class Result {
        public final Op rewrittenOp;
        public final AliasRewriteState state;

        Result(final Op rewrittenOp, final AliasRewriteState state) {
            this.rewrittenOp = rewrittenOp;
            this.state = state;
        }
    }

    public static Result rewrite(final Op op, final AliasMap aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return new Result(op, AliasRewriteState.inactive());
        }
        final Map<String, String> reverse = new HashMap<>();
        final NodeTransform xform = new NodeTransform() {
            @Override
            public Node apply(final Node node) {
                if (node == null || !node.isURI()) {
                    return node;
                }
                final String iri = node.getURI();
                return aliases.canonicalOf(iri)
                        .map(canonical -> {
                            reverse.putIfAbsent(canonical, iri);
                            return NodeFactory.createURI(canonical);
                        })
                        .orElse(node);
            }
        };
        final Op rewritten = NodeTransformLib.transform(xform, op);
        return new Result(rewritten, new AliasRewriteState(reverse));
    }
}
