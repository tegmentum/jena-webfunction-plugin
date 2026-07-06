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
 * SPARQL SERVICE test: {@code SERVICE <wasm-url> { BIND(...) }} exposes the
 * component's {@code binding-sets} return as SERVICE result bindings.
 */
public class TestWfCallService {

    private static final String TO_UPPER_WASM =
            System.getProperty("wf.toUpper.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm");

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Test
    public void serviceReturnsUppercasedRow() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final String queryString =
                "SELECT ?value_0 WHERE {\n" +
                "  SERVICE <" + wasm.toURI() + "> {\n" +
                "    BIND(\"stardog\" AS ?arg0)\n" +
                "  }\n" +
                "}";

        final Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, ModelFactory.createDefaultModel())) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution soln = rs.next();
            assertThat(soln.getLiteral("value_0").getLexicalForm()).isEqualTo("STARDOG");
            assertThat(rs.hasNext()).isFalse();
        }
    }
}
