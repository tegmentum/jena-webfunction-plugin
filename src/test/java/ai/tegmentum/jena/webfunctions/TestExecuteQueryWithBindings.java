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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the v0.6 {@code execute-query-with-bindings} host callback.
 * Drives the {@link HostCallbacks#executeQueryWithBindings()} closure
 * directly with a hand-shaped {@code binding-sets} seed rather than going
 * through wasm — the test isn't about linker plumbing (that's covered by
 * the other host callback tests via {@code debug_*.wasm}), it's about
 * seed → Jena Table conversion and the outer VALUES-join semantics.
 *
 * <p>Fixture: three people, all typed {@code ex:Person}. Seed is a two-row
 * matrix (?p = ex:Alice, ex:Bob). The query looks up {@code ?p rdf:type ?t}
 * and returns rows for those two subjects only. Verifies:
 *   1. The seed rows join with the query pattern (only ex:Alice and ex:Bob
 *      come back — ex:Carol is filtered out because she is not in the seed).
 *   2. The returned binding-sets record shape is correct (vars + rows).
 *   3. A malformed seed (missing rows field) surfaces as a clean Err.
 */
public class TestExecuteQueryWithBindings {

    private static final String EX = "http://example.org/";

    private static ComponentVal iri(final String s) {
        return ComponentVal.variant("iri", ComponentVal.string(s));
    }

    private static ComponentVal bindingRow(final String var, final ComponentVal value) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("name", ComponentVal.string(var));
        fields.put("value", value);
        return ComponentVal.record(fields);
    }

    private static ComponentVal bindingSets(final List<String> vars,
                                            final List<List<ComponentVal>> rows) {
        final List<ComponentVal> varsVals = new ArrayList<>();
        for (String v : vars) varsVals.add(ComponentVal.string(v));
        final List<ComponentVal> rowVals = new ArrayList<>();
        for (List<ComponentVal> row : rows) rowVals.add(ComponentVal.list(row));
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("vars", ComponentVal.list(varsVals));
        fields.put("rows", ComponentVal.list(rowVals));
        return ComponentVal.record(fields);
    }

    private static Dataset buildDataset() {
        final Model m = ModelFactory.createDefaultModel();
        final Property type = m.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        final Resource person = m.createResource(EX + "Person");
        m.add(m.createResource(EX + "Alice"), type, person);
        m.add(m.createResource(EX + "Bob"), type, person);
        m.add(m.createResource(EX + "Carol"), type, person);
        return DatasetFactory.create(m);
    }

    private static CallbackContext bindCtx(final Dataset ds) {
        final FunctionEnv env = new org.apache.jena.sparql.function.FunctionEnvBase(
                new Context(), ds.asDatasetGraph().getDefaultGraph(), ds.asDatasetGraph());
        return CallbackContext.bind(env);
    }

    @Test
    public void seedTwoRowsJoinsWithQueryPattern() {
        final Dataset ds = buildDataset();
        final CallbackContext ctx = bindCtx(ds);
        try {
            final WitHostFunction fn = HostCallbacks.executeQueryWithBindings();
            final List<List<ComponentVal>> rows = new ArrayList<>();
            rows.add(java.util.List.of(bindingRow("p", iri(EX + "Alice"))));
            rows.add(java.util.List.of(bindingRow("p", iri(EX + "Bob"))));
            final ComponentVal seed = bindingSets(java.util.List.of("p"), rows);
            final String sparql =
                "SELECT ?p ?t WHERE { ?p <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?t }";

            final Object[] out = fn.execute(new Object[] {
                ComponentVal.string(sparql), seed, ComponentVal.none()
            });
            assertThat(out).hasSize(1);
            final ComponentVal result = (ComponentVal) out[0];
            final ComponentVal ok = result.asResult().getOk().orElseThrow(
                () -> new AssertionError("expected Ok, got err: "
                    + result.asResult().getErr().map(ComponentVal::asString).orElse("<none>")));

            final Map<String, ComponentVal> bs = ok.asRecord();
            final List<ComponentVal> varsList = bs.get("vars").asList();
            final List<ComponentVal> rowsOut = bs.get("rows").asList();

            // Two rows expected — Alice and Bob — Carol filtered out by the seed.
            assertThat(rowsOut).hasSize(2);
            final List<String> subjectIris = new ArrayList<>();
            for (ComponentVal row : rowsOut) {
                for (ComponentVal b : row.asList()) {
                    final Map<String, ComponentVal> bf = b.asRecord();
                    if ("p".equals(bf.get("name").asString())) {
                        subjectIris.add(bf.get("value").asVariant()
                            .getPayload().orElseThrow().asString());
                    }
                }
            }
            assertThat(subjectIris).containsExactlyInAnyOrder(EX + "Alice", EX + "Bob");
            assertThat(varsList).extracting(ComponentVal::asString)
                .contains("p", "t");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    @Test
    public void malformedSeedSurfacesAsErr() {
        final Dataset ds = buildDataset();
        final CallbackContext ctx = bindCtx(ds);
        try {
            final WitHostFunction fn = HostCallbacks.executeQueryWithBindings();
            // Missing `rows` field — record only carries `vars`.
            final Map<String, ComponentVal> bad = new LinkedHashMap<>();
            bad.put("vars", ComponentVal.list(java.util.List.of(ComponentVal.string("p"))));
            final ComponentVal seed = ComponentVal.record(bad);

            final Object[] out = fn.execute(new Object[] {
                ComponentVal.string("SELECT ?p WHERE { ?p ?a ?b }"),
                seed, ComponentVal.none()
            });
            final ComponentVal result = (ComponentVal) out[0];
            final ComponentVal err = result.asResult().getErr().orElseThrow(
                () -> new AssertionError("expected Err from malformed seed, got ok"));
            assertThat(err.asString()).contains("seed missing");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }
}
