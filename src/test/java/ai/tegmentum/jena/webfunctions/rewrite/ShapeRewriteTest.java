package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ShapeRewriteTest {

    private static final String NAME_PRED = "http://example.com/name";
    private static final String AGE_PRED = "http://example.com/age";
    private static final String PERSON_CLASS = "http://example.com/Person";

    private static final String WF_FETCH_URL = "file:///tmp/wf_fetch.wasm";

    private static ShapeRegistry personShapeRegistry() {
        final Map<String, String> columnsByPredicate = new HashMap<>();
        columnsByPredicate.put(NAME_PRED, "name");
        columnsByPredicate.put(AGE_PRED, "age");
        final ShapeRegistry.ShapeEntry entry = new ShapeRegistry.ShapeEntry(
                "person",
                "{\"name\":\"person\",\"anchor\":{\"class\":\"" + PERSON_CLASS + "\"},"
                        + "\"columns\":[{\"name\":\"id\",\"role\":\"subject_iri\"},"
                        + "{\"name\":\"name\",\"role\":\"data\",\"predicate\":\"" + NAME_PRED + "\"},"
                        + "{\"name\":\"age\",\"role\":\"data\",\"predicate\":\"" + AGE_PRED + "\"}]}",
                PERSON_CLASS,
                columnsByPredicate,
                "id");
        return ShapeRegistry.of(java.util.List.of(entry));
    }

    @Test
    public void singleShapeBgpBecomesServiceEnvelope() {
        final BasicPattern bp = new BasicPattern();
        final Var s = Var.alloc("s");
        bp.add(Triple.create(s, NodeFactory.createURI(NAME_PRED), Var.alloc("n")));
        bp.add(Triple.create(s, NodeFactory.createURI(AGE_PRED), Var.alloc("a")));
        final Op input = new OpBGP(bp);

        final Op out = ShapeRewrite.rewrite(input, personShapeRegistry(), WF_FETCH_URL);

        assertThat(out).isInstanceOf(OpService.class);
        final OpService svc = (OpService) out;
        assertThat(svc.getService().getURI()).isEqualTo(ShapeRewrite.WF_CALL_IRI);
        final OpBGP inner = (OpBGP) svc.getSubOp();
        // Config triples (wasm URL + descriptor) + one subject-column
        // binding + two data columns = 5.
        assertThat(inner.getPattern().size()).isEqualTo(5);
    }

    @Test
    public void mixedBgpWithForeignPredicateIsNotRewritten() {
        final BasicPattern bp = new BasicPattern();
        final Var s = Var.alloc("s");
        bp.add(Triple.create(s, NodeFactory.createURI(NAME_PRED), Var.alloc("n")));
        // Foreign predicate — not registered against any shape.
        bp.add(Triple.create(s,
                NodeFactory.createURI("http://example.com/pet"),
                Var.alloc("p")));
        final Op input = new OpBGP(bp);

        final Op out = ShapeRewrite.rewrite(input, personShapeRegistry(), WF_FETCH_URL);
        // Falls through to a plain BGP copy — no SERVICE wrapping.
        assertThat(out).isInstanceOf(OpBGP.class);
    }

    @Test
    public void classAnchorMismatchAbortsRewrite() {
        final BasicPattern bp = new BasicPattern();
        final Var s = Var.alloc("s");
        bp.add(Triple.create(s,
                NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                NodeFactory.createURI("http://example.com/NotAPerson")));
        bp.add(Triple.create(s, NodeFactory.createURI(NAME_PRED), Var.alloc("n")));
        final Op input = new OpBGP(bp);

        final Op out = ShapeRewrite.rewrite(input, personShapeRegistry(), WF_FETCH_URL);
        assertThat(out).isInstanceOf(OpBGP.class);
    }

    @Test
    public void graphVarWrapsServiceInExtendBindingVirtualIri() {
        // `GRAPH ?g { ?s :name ?n; :age ?a }` — the rewrite drops
        // the OpGraph and wraps the SERVICE with an OpExtend that
        // binds ?g to the shape's virtual IRI.
        final Op input = Algebra.compile(QueryFactory.create(
                "PREFIX ex: <http://example.com/>\n"
                        + "SELECT * WHERE { GRAPH ?g { ?s ex:name ?n ; ex:age ?a } }"));
        final Op out = ShapeRewrite.rewrite(input, personShapeRegistry(), WF_FETCH_URL);
        // The OpGraph must be gone.
        assertThat(collectByClass(out, OpGraph.class)).isEmpty();
        // An OpExtend must bind ?g to the shape virtual IRI.
        assertThat(collectByClass(out, OpExtend.class)).anySatisfy(ext -> {
            assertThat(ext.getVarExprList().getVars())
                    .contains(Var.alloc("g"));
            assertThat(ext.getVarExprList().getExpr(Var.alloc("g")).toString())
                    .contains(ShapeRewrite.shapeVirtualGraphIri("person"));
        });
        // The SERVICE wf:call must have been emitted.
        assertThat(collectByClass(out, OpService.class)).hasSize(1);
    }

    @Test
    public void graphMatchingVirtualIriUnwrapsToPlainService() {
        // `GRAPH <urn:wf:shape:person> { ... }` — outer graph IRI
        // matches the shape's virtual IRI, so the OpGraph wrapper
        // drops entirely, no OpExtend, plain OpService.
        final String virt = ShapeRewrite.shapeVirtualGraphIri("person");
        final Op input = Algebra.compile(QueryFactory.create(
                "PREFIX ex: <http://example.com/>\n"
                        + "SELECT * WHERE { GRAPH <" + virt + "> { ?s ex:name ?n ; ex:age ?a } }"));
        final Op out = ShapeRewrite.rewrite(input, personShapeRegistry(), WF_FETCH_URL);
        assertThat(collectByClass(out, OpGraph.class)).isEmpty();
        assertThat(collectByClass(out, OpExtend.class)).isEmpty();
        assertThat(collectByClass(out, OpService.class)).hasSize(1);
    }

    @Test
    public void graphWithForeignIriLeavesRewriteDisabled() {
        // `GRAPH <http://example.com/other> { ... }` — the outer IRI
        // isn't the shape's virtual IRI, so the rewrite refuses. The
        // OpGraph survives; no SERVICE is emitted.
        final Op input = Algebra.compile(QueryFactory.create(
                "PREFIX ex: <http://example.com/>\n"
                        + "SELECT * WHERE { GRAPH ex:other { ?s ex:name ?n ; ex:age ?a } }"));
        final Op out = ShapeRewrite.rewrite(input, personShapeRegistry(), WF_FETCH_URL);
        assertThat(collectByClass(out, OpGraph.class)).hasSize(1);
        assertThat(collectByClass(out, OpService.class)).isEmpty();
    }

    private static <T> java.util.List<T> collectByClass(final Op root, final Class<T> cls) {
        final java.util.List<T> out = new java.util.ArrayList<>();
        org.apache.jena.sparql.algebra.OpWalker.walk(root,
                new org.apache.jena.sparql.algebra.OpVisitorBase() {
                    @Override public void visit(final org.apache.jena.sparql.algebra.op.OpGraph op) {
                        if (cls.isInstance(op)) out.add(cls.cast(op));
                    }
                    @Override public void visit(final org.apache.jena.sparql.algebra.op.OpService op) {
                        if (cls.isInstance(op)) out.add(cls.cast(op));
                    }
                    @Override public void visit(final org.apache.jena.sparql.algebra.op.OpExtend op) {
                        if (cls.isInstance(op)) out.add(cls.cast(op));
                    }
                });
        return out;
    }

    @Test
    public void emptyRegistryIsIdentity() {
        final BasicPattern bp = new BasicPattern();
        final Var s = Var.alloc("s");
        bp.add(Triple.create(s, NodeFactory.createURI(NAME_PRED), Var.alloc("n")));
        final Op input = new OpBGP(bp);
        final Op out = ShapeRewrite.rewrite(input, ShapeRegistry.empty(), WF_FETCH_URL);
        assertThat(out).isSameAs(input);
    }
}
