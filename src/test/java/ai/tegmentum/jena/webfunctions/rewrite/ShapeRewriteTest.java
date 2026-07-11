package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
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
    public void emptyRegistryIsIdentity() {
        final BasicPattern bp = new BasicPattern();
        final Var s = Var.alloc("s");
        bp.add(Triple.create(s, NodeFactory.createURI(NAME_PRED), Var.alloc("n")));
        final Op input = new OpBGP(bp);
        final Op out = ShapeRewrite.rewrite(input, ShapeRegistry.empty(), WF_FETCH_URL);
        assertThat(out).isSameAs(input);
    }
}
