package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.rdf.model.RDFNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Per-query rewrite state. Populated during the input rewrite pass
 * (canonical &rarr; original alias reverse map), consulted during the
 * output rewrite so returned IRIs read as the alias the caller asked
 * about. Bare canonicals the caller didn't mention pass through
 * unchanged.
 *
 * <p>Mirrors {@code oxigraph-wf/src/alias_rewrite.rs::AliasRewrite}.
 */
public final class AliasRewriteState {

    private final Map<String, String> reverse;

    AliasRewriteState(final Map<String, String> reverse) {
        this.reverse = Collections.unmodifiableMap(reverse);
    }

    public static AliasRewriteState inactive() {
        return new AliasRewriteState(new HashMap<>());
    }

    /**
     * If {@code iri} is a canonical the caller previously mentioned as
     * an alias, return the original alias so the output path can restore
     * it. Otherwise empty.
     */
    public Optional<String> recoverAlias(final String iri) {
        return Optional.ofNullable(reverse.get(iri));
    }

    public boolean isActive() {
        return !reverse.isEmpty();
    }

    /**
     * Rewrite one node. IRI values whose canonical is in the reverse map
     * come back as their alias; every other value passes through.
     */
    public Node rewriteNode(final Node n) {
        if (reverse.isEmpty() || n == null || !n.isURI()) {
            return n;
        }
        final String alias = reverse.get(n.getURI());
        return alias == null ? n : NodeFactory.createURI(alias);
    }

    /**
     * Rewrite a CONSTRUCT/DESCRIBE triple's IRIs. Same rules as
     * solutions — only mentioned canonicals get rewritten back.
     */
    public Triple rewriteTriple(final Triple t) {
        if (reverse.isEmpty()) {
            return t;
        }
        return Triple.create(rewriteNode(t.getSubject()),
                rewriteNode(t.getPredicate()),
                rewriteNode(t.getObject()));
    }

    /**
     * Return a new {@link QuerySolution} whose {@link Node} values have
     * been rewritten. Cheap identity when the reverse map is empty.
     */
    public QuerySolution rewriteSolution(final QuerySolution sol) {
        if (reverse.isEmpty()) {
            return sol;
        }
        final QuerySolutionMap out = new QuerySolutionMap();
        final Iterator<String> it = sol.varNames();
        while (it.hasNext()) {
            final String v = it.next();
            final RDFNode value = sol.get(v);
            if (value == null) {
                continue;
            }
            if (value.isURIResource()) {
                final String canonical = value.asResource().getURI();
                final String alias = reverse.get(canonical);
                if (alias != null) {
                    out.add(v, value.getModel().createResource(alias));
                    continue;
                }
            }
            out.add(v, value);
        }
        return out;
    }
}
