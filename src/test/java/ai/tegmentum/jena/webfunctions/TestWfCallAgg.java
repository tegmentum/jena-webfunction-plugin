package ai.tegmentum.jena.webfunctions;

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
 * End-to-end SPARQL test for the {@code wf:call-agg} custom aggregate, backed
 * by the shared {@code sum_component.wasm} built for the Stardog binding.
 */
public class TestWfCallAgg {

    private static final String SUM_WASM =
            System.getProperty("wf.sum.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/sum_component.wasm");

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Test
    public void sumAggregatesRowValues() {
        final File wasm = new File(SUM_WASM);
        assumeTrue("sum_component.wasm not found at " + wasm
                        + " (override with -Dwf.sum.wasm=...)",
                wasm.exists());

        final Model model = ModelFactory.createDefaultModel();
        // Load three integer facts: 10, 20, 3 → sum 33 (multiplicity is always
        // 1 in Jena's aggregate model).
        model.read(new java.io.StringReader(
                "@prefix ex: <http://example.com/> ."
                        + " ex:a ex:val 10 . ex:b ex:val 20 . ex:c ex:val 3 ."),
                null, "TTL");

        final String queryString =
                "PREFIX wf: <" + WfCall.NAMESPACE + ">\n" +
                "PREFIX ex: <http://example.com/>\n" +
                "SELECT (<" + WfCallAgg.URI + ">(<" + wasm.toURI() + ">, ?v) AS ?total) WHERE {\n" +
                "  ?s ex:val ?v .\n" +
                "}";

        final Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution soln = rs.next();
            assertThat(soln.getLiteral("total").getLexicalForm()).isEqualTo("33");
            assertThat(rs.hasNext()).isFalse();
        }
    }
}
