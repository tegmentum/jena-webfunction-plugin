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
            WasmFixtures.exampleUppercaseWasm();

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

    // Multi-var multi-row SERVICE test retired alongside the
    // stardog-plugin-local multi_var_component fixture. The base
    // sparql-extension filter interface returns a single term; the
    // multi-row multi-var shape belongs to the property-function
    // surface. The replacement webfunctions example-multi-var-filter
    // preserves only the 2-arg describe filter — the 3-var 2-row
    // shape this test asserted no longer has a replacement fixture.

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
