package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AliasRewriteTest {

    private static final String ALIAS = "http://example.com/alice";
    private static final String CANONICAL = "http://example.com/.well-known/genid/1";

    @Test
    public void inputBgpIriIsSubstitutedAndReverseMapPopulated() {
        final Map<String, String> aliases = new HashMap<>();
        aliases.put(ALIAS, CANONICAL);
        final AliasMap map = AliasMap.of(aliases);

        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(
                NodeFactory.createURI(ALIAS),
                NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
                Var.alloc("friend")));
        final Op input = new OpBGP(bp);

        final AliasRewrite.Result res = AliasRewrite.rewrite(input, map);

        // The BGP now references the canonical form.
        final OpBGP rewritten = (OpBGP) res.rewrittenOp;
        final Triple only = rewritten.getPattern().iterator().next();
        assertThat(only.getSubject().getURI()).isEqualTo(CANONICAL);

        // The state records the reverse mapping so the output serializer
        // can restore the alias for the caller.
        assertThat(res.state.isActive()).isTrue();
        assertThat(res.state.recoverAlias(CANONICAL)).contains(ALIAS);
        assertThat(res.state.recoverAlias("http://example.com/other")).isEmpty();
    }

    @Test
    public void emptyMapShortCircuitsToIdentity() {
        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(
                NodeFactory.createURI(ALIAS),
                NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
                Var.alloc("friend")));
        final Op input = new OpBGP(bp);

        final AliasRewrite.Result res = AliasRewrite.rewrite(input, AliasMap.empty());
        assertThat(res.rewrittenOp).isSameAs(input);
        assertThat(res.state.isActive()).isFalse();
    }

    @Test
    public void outputSolutionRewriteRestoresAliasForMentionedCanonical() {
        final Map<String, String> aliases = new HashMap<>();
        aliases.put(ALIAS, CANONICAL);
        final AliasMap map = AliasMap.of(aliases);

        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(
                NodeFactory.createURI(ALIAS),
                NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
                Var.alloc("friend")));
        final AliasRewrite.Result res = AliasRewrite.rewrite(new OpBGP(bp), map);

        // Simulate a solution row whose subject came back as the canonical.
        final QuerySolutionMap sol = new QuerySolutionMap();
        sol.add("s", ModelFactory.createDefaultModel().createResource(CANONICAL));
        sol.add("friend", ModelFactory.createDefaultModel().createResource(
                "http://example.com/bob"));

        final var rewritten = res.state.rewriteSolution(sol);
        // Mentioned canonical → back to alias.
        assertThat(rewritten.get("s").asResource().getURI()).isEqualTo(ALIAS);
        // Unmentioned URI → untouched.
        assertThat(rewritten.get("friend").asResource().getURI())
                .isEqualTo("http://example.com/bob");
    }
}
