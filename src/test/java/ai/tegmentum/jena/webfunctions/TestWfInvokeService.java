package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.jena.webfunctions.rewrite.AliasMap;
import ai.tegmentum.jena.webfunctions.rewrite.ConversionRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.InvokeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.RewritePipeline;
import ai.tegmentum.jena.webfunctions.rewrite.ShapeRegistry;
import ai.tegmentum.jena.webfunctions.rewrite.WebFunctionQueryEngine;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end coverage for the {@link WfInvokeService} SERVICE handler that
 * dispatches {@code SERVICE <wf-invoke:<hex-id>>} — the folded form
 * produced by {@code PartialRewrite}.
 *
 * <p>The test bypasses the rewrite pass and instead pre-populates the
 * plugin's {@link InvokeRegistry} directly: this isolates the SERVICE
 * dispatcher from the fold logic, which has its own unit test coverage
 * ({@code PartialRewriteTest}). Together they exercise the whole
 * partial-application path end-to-end.
 */
public class TestWfInvokeService {

    private static final String TO_UPPER_WASM =
            WasmFixtures.exampleUppercaseWasm();

    @BeforeClass
    public static void registerFunctions() {
        WebFunctionInit.register();
    }

    @Before
    @After
    public void resetPipeline() {
        ARQ.getContext().remove(WebFunctionQueryEngine.PIPELINE_SYMBOL);
        ARQ.getContext().remove(WebFunctionQueryEngine.ALIAS_STATE_SYMBOL);
    }

    /**
     * Pre-populate the registry with an InvokeSpec targeting
     * {@code to_upper_component.wasm} and the literal "stardog", run a
     * SELECT with {@code SERVICE <wf-invoke:0>}, and assert the guest's
     * {@code value_0} column comes back with the uppercased form.
     */
    @Test
    public void wfInvokeServiceDispatchesFoldedSpec() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final InvokeRegistry invokeRegistry = new InvokeRegistry();
        final long id = invokeRegistry.insert(new InvokeRegistry.InvokeSpec(
                wasm.toURI().toString(),
                List.of(NodeFactory.createLiteralString("stardog"))));

        final RewritePipeline.Context pipelineCtx = new RewritePipeline.Context(
                invokeRegistry,
                ConversionRegistry.empty(),
                AliasMap.empty(),
                ShapeRegistry.empty(),
                null);
        WebFunctionQueryEngine.installGlobal(pipelineCtx);

        final String invokeIri = InvokeRegistry.iriFor(id);
        final String queryString =
                "PREFIX wf: <http://tegmentum.ai/ns/webfunction/>\n" +
                "SELECT ?value_0 WHERE {\n" +
                "  SERVICE <" + invokeIri + "> { ?_ wf:value_0 ?value_0 }\n" +
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
     * When the SERVICE body carries no {@code _:o wf:<col> ?var} triples,
     * the dispatcher falls back to using the guest's own column names as
     * caller variables — mirroring the Rust WfPartialDispatchHandler.
     */
    @Test
    public void wfInvokeServiceFallsBackToGuestColumnNames() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final InvokeRegistry invokeRegistry = new InvokeRegistry();
        final long id = invokeRegistry.insert(new InvokeRegistry.InvokeSpec(
                wasm.toURI().toString(),
                List.of(NodeFactory.createLiteralString("jena"))));

        final RewritePipeline.Context pipelineCtx = new RewritePipeline.Context(
                invokeRegistry,
                ConversionRegistry.empty(),
                AliasMap.empty(),
                ShapeRegistry.empty(),
                null);
        WebFunctionQueryEngine.installGlobal(pipelineCtx);

        final String invokeIri = InvokeRegistry.iriFor(id);
        final String queryString =
                "SELECT ?value_0 WHERE {\n" +
                "  SERVICE <" + invokeIri + "> { }\n" +
                "}";

        final Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, ModelFactory.createDefaultModel())) {
            final ResultSet rs = qe.execSelect();
            assertThat(rs.hasNext()).isTrue();
            final QuerySolution soln = rs.next();
            assertThat(soln.getLiteral("value_0").getLexicalForm()).isEqualTo("JENA");
        }
    }

    /**
     * A registry that never allocated the id must produce a QueryException,
     * not silently return empty results — otherwise a mis-configured plan
     * would hide the bug.
     */
    @Test
    public void wfInvokeServiceUnknownIdFails() {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper_component.wasm not found at " + wasm, wasm.exists());

        final InvokeRegistry invokeRegistry = new InvokeRegistry();
        final RewritePipeline.Context pipelineCtx = new RewritePipeline.Context(
                invokeRegistry,
                ConversionRegistry.empty(),
                AliasMap.empty(),
                ShapeRegistry.empty(),
                null);
        WebFunctionQueryEngine.installGlobal(pipelineCtx);

        final String queryString =
                "SELECT ?x WHERE { SERVICE <wf-invoke:deadbeef> { } }";

        final Query query = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(query, ModelFactory.createDefaultModel())) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> qe.execSelect().hasNext())
                    .hasMessageContaining("wf-invoke");
        }
    }
}
