package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ConversionRewriteTest {

    private static final String WEIGHT_KG = "http://example.com/weight_kg";
    private static final String WEIGHT_LB = "http://example.com/weight_lb";

    private static ConversionRegistry registry() {
        final ConversionRegistry.RawRule rule = new ConversionRegistry.RawRule(
                WEIGHT_KG, WEIGHT_LB, "?source * 0.453592");
        return ConversionRegistry.of(List.of(rule));
    }

    @Test
    public void specificGraphExpandsToSourceBgpPlusBind() {
        final ConversionRegistry reg = registry();
        final ConversionRegistry.ConversionRule rule =
                reg.rules().iterator().next();

        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(
                Var.alloc("item"),
                NodeFactory.createURI(WEIGHT_KG),
                Var.alloc("kg")));
        final Op graphOp = new OpGraph(NodeFactory.createURI(rule.graphIri), new OpBGP(bp));

        final Op out = ConversionRewrite.rewrite(graphOp, reg);

        // Extend { var = expr } wrapping a BGP against source predicate.
        assertThat(out).isInstanceOf(OpExtend.class);
        final OpExtend extend = (OpExtend) out;
        assertThat(extend.getVarExprList().getVars())
                .containsExactly(Var.alloc("kg"));
        final OpBGP inner = (OpBGP) extend.getSubOp();
        final Triple only = inner.getPattern().iterator().next();
        assertThat(only.getPredicate().getURI()).isEqualTo(WEIGHT_LB);
    }

    @Test
    public void variableGraphSingleTripleBecomesUnionOverRules() {
        final ConversionRegistry.RawRule r1 = new ConversionRegistry.RawRule(
                WEIGHT_KG, WEIGHT_LB, "?source * 0.453592");
        final ConversionRegistry.RawRule r2 = new ConversionRegistry.RawRule(
                WEIGHT_KG, "http://example.com/weight_stone", "?source * 6.35029");
        final ConversionRegistry reg = ConversionRegistry.of(List.of(r1, r2));

        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(
                Var.alloc("item"),
                NodeFactory.createURI(WEIGHT_KG),
                Var.alloc("kg")));
        final Op graphOp = new OpGraph(
                NodeFactory.createVariable("g"),
                new OpBGP(bp));

        final Op out = ConversionRewrite.rewrite(graphOp, reg);
        // Two rules → Union of two branches.
        assertThat(out).isInstanceOf(OpUnion.class);
    }

    @Test
    public void defaultGraphBgpUnchanged() {
        final ConversionRegistry reg = registry();
        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(
                Var.alloc("item"),
                NodeFactory.createURI(WEIGHT_KG),
                Var.alloc("kg")));
        final Op bgp = new OpBGP(bp);

        final Op out = ConversionRewrite.rewrite(bgp, reg);
        // No GRAPH wrapper → nothing to rewrite. The transformer copies
        // the BGP shape, so identity-by-equals rather than identity-by-ref.
        assertThat(out).isInstanceOf(OpBGP.class);
        assertThat(((OpBGP) out).getPattern().iterator().next().getPredicate().getURI())
                .isEqualTo(WEIGHT_KG);
    }

    @Test
    public void nonConversionGraphIriUnchanged() {
        final ConversionRegistry reg = registry();
        final BasicPattern bp = new BasicPattern();
        bp.add(Triple.create(
                Var.alloc("item"),
                NodeFactory.createURI(WEIGHT_KG),
                Var.alloc("kg")));
        final Op graphOp = new OpGraph(
                NodeFactory.createURI("http://example.com/some_normal_graph"),
                new OpBGP(bp));
        final Op out = ConversionRewrite.rewrite(graphOp, reg);
        assertThat(out).isInstanceOf(OpGraph.class);
    }
}
