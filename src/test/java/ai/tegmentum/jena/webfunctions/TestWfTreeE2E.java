package ai.tegmentum.jena.webfunctions;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Cross-engine portability proof: the same {@code wf_tree.wasm} binary the
 * RDF4J {@code TestWfTreeE2E} exercises is loaded here into Apache Jena and
 * asked to walk the same tree-shaped graph via the v0.3.0 {@code execute-query}
 * host import. If the JSON tree comes back with the same URIs, Paper 2's
 * headline claim — "one wasm binary, three engines" — has an executable
 * witness.
 *
 * <p>The graph is A → B, A → C, B → D, C → E, with {@code ex:hasChild} as
 * the parent-child predicate.
 */
public class TestWfTreeE2E {

    private static final String WF_TREE_WASM = System.getProperty("wf.tree.wasm",
            System.getProperty("user.home")
                    + "/git/tegmentum-webfunctions/target/wasm32-wasip1/release/wf_tree.wasm");

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Test
    public void tinyTreeFromRoot() {
        final File wasm = new File(WF_TREE_WASM);
        assumeTrue("wf_tree.wasm not built at " + wasm.getAbsolutePath()
                + " (build via `cargo component build --release` in "
                + "tegmentum-webfunctions/crates/wf_tree)",
                wasm.exists());

        final Model model = ModelFactory.createDefaultModel();
        final Property has = model.createProperty("http://example.org/hasChild");
        final Resource a = model.createResource("http://example.org/A");
        final Resource b = model.createResource("http://example.org/B");
        final Resource c = model.createResource("http://example.org/C");
        final Resource d = model.createResource("http://example.org/D");
        final Resource e = model.createResource("http://example.org/E");
        model.add(a, has, b);
        model.add(a, has, c);
        model.add(b, has, d);
        model.add(c, has, e);

        final Dataset ds = DatasetFactory.create(model);

        final String sparql =
            "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
            "PREFIX ex: <http://example.org/>\n" +
            "SELECT ?tree WHERE {\n" +
            "  BIND (wf:call(\n" +
            "        <" + wasm.toURI() + ">,\n" +
            "        ex:A,\n" +
            "        \"SELECT ?child WHERE { ?this <http://example.org/hasChild> ?child }\"" +
            "  ) AS ?tree)\n" +
            "}";

        final Query q = QueryFactory.create(sparql);
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution row = rs.next();
            final String tree = row.getLiteral("tree").getLexicalForm();

            assertThat(tree).contains("\"uri\":\"http://example.org/A\"");
            assertThat(tree).contains("\"uri\":\"http://example.org/B\"");
            assertThat(tree).contains("\"uri\":\"http://example.org/C\"");
            assertThat(tree).contains("\"uri\":\"http://example.org/D\"");
            assertThat(tree).contains("\"uri\":\"http://example.org/E\"");
            assertThat(tree).contains("\"children\":");
        }
    }
}
