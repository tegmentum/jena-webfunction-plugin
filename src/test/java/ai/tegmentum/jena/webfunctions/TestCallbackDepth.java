package ai.tegmentum.jena.webfunctions;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Jena counterpart of the RDF4J {@code TestCallbackDepth}. Loads the shared
 * {@code debug_callback_depth.wasm} — a component that just calls
 * {@code host::callback-depth()} — and asserts it returns 0 at the top level.
 *
 * <p>If this passes on Jena while using the exact same wasm bytes the RDF4J
 * plugin uses, that's the minimal proof that host-import wiring is engine-
 * agnostic at the linker layer.
 */
public class TestCallbackDepth {

    private static final String WASM = System.getProperty("wf.debug.callback.depth.wasm",
            System.getProperty("user.home")
                    + "/git/webfunctions/target/wasm32-wasip1/release/debug_callback_depth.wasm");

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Test
    public void depthIsZeroAtTopLevel() {
        final File wasm = new File(WASM);
        assumeTrue("debug_callback_depth.wasm not built at " + wasm.getAbsolutePath(),
                wasm.exists());

        final String sparql =
            "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
            "SELECT ?depth WHERE {\n" +
            "  BIND (wf:call(<" + wasm.toURI() + ">) AS ?depth)\n" +
            "}";

        final Query q = QueryFactory.create(sparql);
        try (QueryExecution qe = QueryExecutionFactory.create(q, ModelFactory.createDefaultModel())) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution row = rs.next();
            assertThat(row.get("depth").toString()).contains("0");
        }
    }
}
