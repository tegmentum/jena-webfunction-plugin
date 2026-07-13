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

    private static final String MULTI_VAR_WASM =
            System.getProperty("wf.multiVar.wasm",
                    System.getProperty("user.home")
                            + "/git/stardog-webfunction-plugin/src/test/rust/target/wasm32-wasip1/release/multi_var_component.wasm");

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

    /**
     * Multi-var multi-row: the WIT {@code binding-sets} record declares a vars
     * list and a rows list of bindings. This test exercises the shape that
     * {@code serviceReturnsUppercasedRow} does not: three columns and two rows.
     */
    @Test
    public void serviceReturnsMultipleRowsAcrossMultipleVars() {
        final File wasm = new File(MULTI_VAR_WASM);
        assumeTrue("multi_var_component.wasm not found at " + wasm, wasm.exists());

        final String queryString =
                "SELECT ?label ?upper ?length WHERE {\n" +
                "  SERVICE <" + wasm.toURI() + "> { }\n" +
                "}";

        final Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, ModelFactory.createDefaultModel())) {
            final ResultSet rs = qe.execSelect();

            assertThat(rs.hasNext()).isTrue();
            final QuerySolution row1 = rs.next();
            assertThat(row1.getLiteral("label").getLexicalForm()).isEqualTo("stardog");
            assertThat(row1.getLiteral("upper").getLexicalForm()).isEqualTo("STARDOG");
            assertThat(row1.getLiteral("length").getLexicalForm()).isEqualTo("7");
            assertThat(row1.getLiteral("length").getDatatypeURI())
                    .isEqualTo("http://www.w3.org/2001/XMLSchema#integer");

            assertThat(rs.hasNext()).isTrue();
            final QuerySolution row2 = rs.next();
            assertThat(row2.getLiteral("label").getLexicalForm()).isEqualTo("jena");
            assertThat(row2.getLiteral("upper").getLexicalForm()).isEqualTo("JENA");
            assertThat(row2.getLiteral("length").getLexicalForm()).isEqualTo("4");

            assertThat(rs.hasNext()).isFalse();
        }
    }

    /**
     * Regression: WfCallService must NOT claim `SERVICE <http://.../query>`
     * URLs (SPARQL endpoints emitted by the wf_federation rewrite pass).
     * Before this check landed, WfCallService accepted every http(s) URL
     * as a wasm-component URL, HTTP-GET'd the SPARQL endpoint, and handed
     * the SPARQL Results JSON to wasmtime4j as component bytes. Under
     * SILENT semantics the whole federated round-trip silently no-op'd
     * with empty bindings — the wf-conformance `federation_sparql_only`
     * case exercises the end-to-end scenario; this test locks the
     * URL-suffix guard on its own so unit tests catch a regression.
     */
    @Test
    public void wasmSuffixGuardRejectsSparqlEndpointUrls() {
        // Positive cases — wasm URLs the executor MUST claim.
        assertThat(WfCallService.hasWasmSuffix("file:///tmp/foo.wasm")).isTrue();
        assertThat(WfCallService.hasWasmSuffix("http://cdn.example/x.wasm")).isTrue();
        assertThat(WfCallService.hasWasmSuffix("https://cdn.example/x.wasm?v=1")).isTrue();
        assertThat(WfCallService.hasWasmSuffix("https://cdn.example/x.wasm#frag")).isTrue();
        assertThat(WfCallService.hasWasmSuffix("ipfs://qmhash/x.wasm")).isTrue();

        // Negative cases — SPARQL endpoints the executor MUST delegate.
        assertThat(WfCallService.hasWasmSuffix("http://127.0.0.1:8080/query")).isFalse();
        assertThat(WfCallService.hasWasmSuffix("https://public.example/sparql")).isFalse();
        assertThat(WfCallService.hasWasmSuffix("http://x/")).isFalse();
        assertThat(WfCallService.hasWasmSuffix("http:")).isFalse();
    }
}
