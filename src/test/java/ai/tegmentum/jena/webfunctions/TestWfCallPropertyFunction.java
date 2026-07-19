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
 * Property-function form of {@code wf:call}. Binds a subject variable to the
 * first column of each row returned by the component's {@code evaluate} export
 * — one binding per row.
 */
public class TestWfCallPropertyFunction {

    private static final String TO_UPPER_WASM =
            WasmFixtures.exampleUppercaseWasm();

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Test
    public void propertyFunctionBindsFirstValue() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final String queryString =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                "SELECT ?result WHERE {\n" +
                "  ?result wf:call (<" + wasm.toURI() + "> \"stardog\") .\n" +
                "}";

        final Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, ModelFactory.createDefaultModel())) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution soln = rs.next();
            assertThat(soln.getLiteral("result").getLexicalForm()).isEqualTo("STARDOG");
            assertThat(rs.hasNext()).isFalse();
        }
    }
}
