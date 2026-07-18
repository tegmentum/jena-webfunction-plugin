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
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end proof of the v0.3.1 {@code execute-update} host import on Jena.
 *
 * <p>The {@code debug_execute_update.wasm} component:
 * <ol>
 *   <li>Takes {@code (s, p, o)} as evaluate() arguments.</li>
 *   <li>Runs {@code host::execute-update} with {@code INSERT DATA {<s> <p> o}}.</li>
 *   <li>Runs {@code host::execute-query} with {@code SELECT ?o WHERE {<s> <p> ?o}}
 *       and asserts the row it just inserted comes back.</li>
 *   <li>Returns {@code true} in a {@code confirmed} binding.</li>
 * </ol>
 *
 * <p>Also asserts the outer dataset now contains the inserted triple — i.e.
 * the update actually landed in the outer transaction, not a scratch copy.
 */
public class TestExecuteUpdate {

    private static final String WASM = System.getProperty("wf.debug.execute.update.wasm",
            System.getProperty("user.home")
                    + "/git/webfunctions/target/wasm32-wasip1/release/debug_execute_update.wasm");

    private static final String S = "http://example.org/subj";
    private static final String P = "http://example.org/pred";
    private static final String O = "hello";

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Test
    public void insertVisibleImmediatelyToNextQueryAndOuterDataset() {
        final File wasm = new File(WASM);
        assumeTrue("debug_execute_update.wasm not built at " + wasm.getAbsolutePath()
                + " (build via `cargo component build --release -p debug_execute_update`)",
                wasm.exists());

        final Model model = ModelFactory.createDefaultModel();
        final Dataset ds = DatasetFactory.create(model);

        final String sparql =
            "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
            "PREFIX ex: <http://example.org/>\n" +
            "SELECT ?confirmed WHERE {\n" +
            "  BIND (wf:call(<" + wasm.toURI() + ">, ex:subj, ex:pred, \"" + O + "\") AS ?confirmed)\n" +
            "}";

        final Query q = QueryFactory.create(sparql);
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution row = rs.next();
            assertThat(row.getLiteral("confirmed").getBoolean())
                .as("the wasm-side SELECT should see the wasm-side INSERT")
                .isTrue();
        }

        // Outer dataset visibility: the update wasn't just a wasm-local
        // scratch — it should be there when we look with a fresh query.
        try (QueryExecution qe2 = QueryExecutionFactory.create(
                QueryFactory.create("SELECT ?o WHERE { <" + S + "> <" + P + "> ?o }"), ds)) {
            final ResultSet rs2 = qe2.execSelect();
            assertThat(rs2.hasNext()).as("outer dataset should see the wasm insert").isTrue();
            assertThat(rs2.next().getLiteral("o").getLexicalForm()).isEqualTo(O);
        }
    }
}
