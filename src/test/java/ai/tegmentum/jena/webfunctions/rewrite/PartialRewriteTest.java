package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.NodeValue;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PartialRewriteTest {

    private static final String WASM_URL = "file:///tmp/wf_apply.wasm";

    @Test
    public void extendWfPartialDissolvesAndSiblingServiceGetsInvokeIri() {
        final InvokeRegistry registry = new InvokeRegistry();

        // Left branch: Extend { ?svc = wf:partial(<WASM>, "hello") } over
        // a trivial BGP.
        final ExprList args = new ExprList();
        args.add(NodeValue.makeNode(NodeFactory.createURI(WASM_URL)));
        args.add(NodeValue.makeString("hello"));
        final Expr partial = new E_Function(InvokeRegistry.WF_PARTIAL_IRI, args);
        final Op leftInner = new OpBGP(new BasicPattern());
        final Op left = OpExtend.create(leftInner, Var.alloc("svc"), partial);

        // Right branch: SERVICE ?svc { BGP }
        final Op right = new OpService(
                NodeFactory.createVariable("svc"),
                new OpBGP(new BasicPattern()),
                false);

        final Op input = OpJoin.create(left, right);
        final Op out = PartialRewrite.rewrite(input, registry);

        // Expect Join(BGP, Service(<wf-invoke:0>, BGP)) — the Extend
        // dissolved into its inner BGP and the SERVICE variable was
        // substituted for the concrete wf-invoke IRI.
        assertThat(out).isInstanceOf(OpJoin.class);
        final OpJoin join = (OpJoin) out;
        assertThat(join.getLeft()).isInstanceOf(OpBGP.class);
        assertThat(join.getRight()).isInstanceOf(OpService.class);
        final OpService svc = (OpService) join.getRight();
        assertThat(svc.getService().isURI()).isTrue();
        assertThat(svc.getService().getURI()).startsWith(InvokeRegistry.WF_INVOKE_SCHEME);

        // Registry recorded one spec.
        assertThat(registry.size()).isEqualTo(1);
        final long id = InvokeRegistry.idFromIri(svc.getService().getURI()).orElseThrow();
        final InvokeRegistry.InvokeSpec spec = registry.take(id).orElseThrow();
        assertThat(spec.wasmUrl).isEqualTo(WASM_URL);
        assertThat(spec.args).hasSize(1);
        assertThat(spec.args.get(0).getLiteralLexicalForm()).isEqualTo("hello");
    }

    @Test
    public void nonPartialExtendIsPreserved() {
        final InvokeRegistry registry = new InvokeRegistry();
        // A plain BIND(1 AS ?x) — not wf:partial, must not dissolve.
        final Op input = OpExtend.create(new OpBGP(new BasicPattern()),
                Var.alloc("x"), NodeValue.makeInteger(1));

        final Op out = PartialRewrite.rewrite(input, registry);

        assertThat(out).isInstanceOf(OpExtend.class);
        assertThat(registry.size()).isEqualTo(0);
    }
}
