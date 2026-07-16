package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.function.FunctionEnv;
import org.apache.jena.sparql.util.Context;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the {@code wf:sagegraph/host@0.1.0#execute-query} host
 * callback backing the wf_sagegraph guest's k-hop SPARQL round-trip.
 *
 * <p>Drives {@link HostCallbacks#sagegraphExecuteQuery()} directly against
 * a real Jena dataset — verifies that (1) the callback resolves the bound
 * {@link CallbackContext}, (2) SPARQL executes against it, (3) the response
 * comes back as SPARQL 1.1 Results JSON in the {@code result<string, string>}
 * Ok arm, and (4) an unbound context surfaces cleanly on the Err arm.
 *
 * <p>The full wf_sagegraph guest e2e lives elsewhere (parallel guest agent
 * still building it); this test just verifies the linker binding + response
 * shape independent of any guest.
 */
public class TestSagegraphExecuteQuery {

    private static final String EX = "http://example.org/";

    private static Dataset buildDataset() {
        final Model m = ModelFactory.createDefaultModel();
        final Property knows = m.createProperty(EX + "knows");
        final Resource alice = m.createResource(EX + "Alice");
        final Resource bob = m.createResource(EX + "Bob");
        final Resource carol = m.createResource(EX + "Carol");
        m.add(alice, knows, bob);
        m.add(alice, knows, carol);
        m.add(bob, knows, carol);
        return DatasetFactory.create(m);
    }

    private static CallbackContext bindCtx(final Dataset ds) {
        final FunctionEnv env = new org.apache.jena.sparql.function.FunctionEnvBase(
                new Context(), ds.asDatasetGraph().getDefaultGraph(), ds.asDatasetGraph());
        return CallbackContext.bind(env);
    }

    @Test
    public void selectReturnsSparqlResultsJson() {
        final Dataset ds = buildDataset();
        final CallbackContext ctx = bindCtx(ds);
        try {
            final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
            final String sparql =
                "SELECT ?o WHERE { <" + EX + "Alice> <" + EX + "knows> ?o } ORDER BY ?o";

            final Object[] out = fn.execute(new Object[] { ComponentVal.string(sparql) });
            assertThat(out).hasSize(1);
            final ComponentVal result = (ComponentVal) out[0];
            final ComponentVal ok = result.asResult().getOk().orElseThrow(
                () -> new AssertionError("expected Ok, got err: "
                    + result.asResult().getErr().map(ComponentVal::asString).orElse("<none>")));

            // Raw SPARQL 1.1 Results JSON — the guest parses it; we assert
            // envelope shape + that both bindings landed.
            final String json = ok.asString();
            assertThat(json).contains("\"head\"");
            assertThat(json).contains("\"vars\"");
            assertThat(json).contains("\"results\"");
            assertThat(json).contains("\"bindings\"");
            assertThat(json).contains(EX + "Bob");
            assertThat(json).contains(EX + "Carol");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    @Test
    public void askReturnsSparqlResultsJsonWithBooleanEnvelope() {
        final Dataset ds = buildDataset();
        final CallbackContext ctx = bindCtx(ds);
        try {
            final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
            // ASK routes through CallbackContext.executeSelect's ASK arm,
            // which materialises a single-row ResultSet with a `_ask` var
            // bound to xsd:boolean. That serialises to the tuple-shape
            // Results JSON — which is fine for the guest since it just
            // needs a stable string it can parse.
            final String sparql =
                "ASK { <" + EX + "Alice> <" + EX + "knows> <" + EX + "Bob> }";

            final Object[] out = fn.execute(new Object[] { ComponentVal.string(sparql) });
            final ComponentVal result = (ComponentVal) out[0];
            final ComponentVal ok = result.asResult().getOk().orElseThrow(
                () -> new AssertionError("expected Ok for ASK, got err"));
            assertThat(ok.asString()).contains("true");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    @Test
    public void noContextBoundSurfacesAsErr() {
        // Explicitly no CallbackContext.bind — this simulates a guest
        // reaching for execute-query outside a wf:call frame.
        final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
        final Object[] out = fn.execute(new Object[] {
            ComponentVal.string("SELECT ?s WHERE { ?s ?p ?o }") });
        final ComponentVal result = (ComponentVal) out[0];
        final ComponentVal err = result.asResult().getErr().orElseThrow(
            () -> new AssertionError("expected Err when no context is bound"));
        assertThat(err.asString()).contains("no context bound");
    }

    @Test
    public void parseErrorSurfacesAsErr() {
        final Dataset ds = buildDataset();
        final CallbackContext ctx = bindCtx(ds);
        try {
            final WitHostFunction fn = HostCallbacks.sagegraphExecuteQuery();
            final Object[] out = fn.execute(new Object[] {
                ComponentVal.string("this is not a sparql query") });
            final ComponentVal result = (ComponentVal) out[0];
            assertThat(result.asResult().getErr()).isPresent();
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }
}
