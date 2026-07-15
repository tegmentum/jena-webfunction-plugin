package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WfRelationalRewrite}. Sibling of
 * {@code oxigraph-wf/src/wf_relational_rewrite.rs::tests}.
 */
public class WfRelationalRewriteTest {

    private static final String WF_CALL_IRI =
            "http://tegmentum.ai/ns/webfunction/call";
    private static final String WF_FETCH_URL = "file:///opt/wf_fetch.wasm";

    private static final String NAME_PRED = "http://example.com/name";
    private static final String TIER_PRED = "http://example.com/tier";

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private static String customersRelationalJson() {
        return """
                {
                  "sources": [{
                    "name": "customers",
                    "type": "wf-relational",
                    "endpoint": "postgres://user@localhost/mydb",
                    "predicates": ["http://example.com/name", "http://example.com/tier"],
                    "relational": {
                      "sink_kind": "postgres",
                      "table": "customers",
                      "subject_column": "id",
                      "anchor": {"class": "http://example.com/Customer"},
                      "columns": [
                        {"name": "id",   "role": "subject_iri", "type": "iri"},
                        {"name": "name", "role": "column",       "type": "string",
                         "predicate": "http://example.com/name"},
                        {"name": "tier", "role": "column",       "type": "string",
                         "predicate": "http://example.com/tier"}
                      ],
                      "emit_provenance": true,
                      "iri_template": "{id}",
                      "schema_version": "1"
                    }
                  }]
                }""";
    }

    private static WfRelationalRegistry customersRegistry() {
        final JsonObject root = JSON.parse(customersRelationalJson());
        return WfRelationalRegistry.fromJson(root);
    }

    private static FederationRegistry customersFederation() {
        final JsonObject root = JSON.parse(customersRelationalJson());
        return FederationRegistry.fromJson(root);
    }

    /** Federation registry that types `customers` as SPARQL instead of WF_RELATIONAL. */
    private static FederationRegistry wrongTypeFederation() {
        return FederationRegistry.of(List.of(
                new FederationSource("customers", SourceType.SPARQL,
                        "http://ex/query", List.of(), OptionalInt.empty())));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Build a SERVICE &lt;wf-relational:name&gt; { ?c ex:name ?name; ex:tier ?tier } Op. */
    private static Op serviceOverCustomers(final String svcIri) {
        final BasicPattern bp = new BasicPattern();
        final Var c = Var.alloc("c");
        bp.add(Triple.create(c, NodeFactory.createURI(NAME_PRED), Var.alloc("name")));
        bp.add(Triple.create(c, NodeFactory.createURI(TIER_PRED), Var.alloc("tier")));
        return new OpService(NodeFactory.createURI(svcIri), new OpBGP(bp), false);
    }

    /** SERVICE &lt;wf-relational:name&gt; { ?c ex:name ?name }. */
    private static Op serviceOverCustomersSingle(final String svcIri) {
        final BasicPattern bp = new BasicPattern();
        final Var c = Var.alloc("c");
        bp.add(Triple.create(c, NodeFactory.createURI(NAME_PRED), Var.alloc("name")));
        return new OpService(NodeFactory.createURI(svcIri), new OpBGP(bp), false);
    }

    private static List<OpService> collectServices(final Op op) {
        final List<OpService> out = new ArrayList<>();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(final OpService s) { out.add(s); }
        });
        return out;
    }

    /**
     * Descend into the emitted SERVICE &lt;wf:call&gt; envelope and return
     * the string lexical form of the {@code wf:arg} literal.
     */
    private static String descriptorArgJson(final Op op) {
        for (OpService s : collectServices(op)) {
            final Node svc = s.getService();
            if (svc == null || !svc.isURI()) continue;
            if (!WF_CALL_IRI.equals(svc.getURI())) continue;
            final Op sub = s.getSubOp();
            if (!(sub instanceof OpBGP bgp)) continue;
            for (Triple t : bgp.getPattern()) {
                final Node p = t.getPredicate();
                if (p.isURI()
                        && "http://tegmentum.ai/ns/webfunction/arg".equals(p.getURI())) {
                    final Node obj = t.getObject();
                    if (obj.isLiteral()) {
                        return obj.getLiteralLexicalForm();
                    }
                }
            }
        }
        throw new AssertionError("no wf:arg literal in emitted Op");
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    @Test
    public void foldsWfRelationalServiceBodyIntoWfCallEnvelope() {
        final Op input = serviceOverCustomers("wf-relational:customers");
        final Op out = WfRelationalRewrite.rewrite(input,
                customersFederation(), customersRegistry(), WF_FETCH_URL);

        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getService().getURI()).isEqualTo(WF_CALL_IRI);
    }

    @Test
    public void foldBakesPostgresSinkKindAndUrlInDescriptor() {
        final Op input = serviceOverCustomersSingle("wf-relational:customers");
        final Op out = WfRelationalRewrite.rewrite(input,
                customersFederation(), customersRegistry(), WF_FETCH_URL);

        final JsonObject d = JSON.parse(descriptorArgJson(out));
        assertThat(d.get("sink_kind").getAsString().value()).isEqualTo("postgres");
        assertThat(d.get("sink").getAsString().value())
                .isEqualTo("postgres://user@localhost/mydb#customers");
        assertThat(d.get("include_graph").getAsBoolean().value()).isFalse();
        assertThat(d.get("table").getAsString().value()).isEqualTo("customers");
        assertThat(d.get("subject_column").getAsString().value()).isEqualTo("id");
    }

    /**
     * Descriptor with {@code emit_provenance = true} +
     * {@code schema_version} set carries both through so the guest can
     * attach {@code ?_shape_version} bindings per row (memo &sect;07
     * provenance sidecar).
     */
    @Test
    public void foldCarriesShapeVersionProvenanceThrough() {
        final Op input = serviceOverCustomersSingle("wf-relational:customers");
        final Op out = WfRelationalRewrite.rewrite(input,
                customersFederation(), customersRegistry(), WF_FETCH_URL);

        final JsonObject d = JSON.parse(descriptorArgJson(out));
        assertThat(d.get("emit_provenance").getAsBoolean().value()).isTrue();
        assertThat(d.get("schema_version").getAsString().value()).isEqualTo("1");
    }

    @Test
    public void emptyRelationalRegistryShortCircuits() {
        final Op input = serviceOverCustomersSingle("wf-relational:customers");
        final Op out = WfRelationalRewrite.rewrite(input,
                customersFederation(), WfRelationalRegistry.empty(), WF_FETCH_URL);

        assertThat(out).isSameAs(input);
        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getService().getURI())
                .isEqualTo("wf-relational:customers");
    }

    @Test
    public void emptyWfFetchUrlShortCircuits() {
        final Op input = serviceOverCustomersSingle("wf-relational:customers");
        final Op out = WfRelationalRewrite.rewrite(input,
                customersFederation(), customersRegistry(), "");

        assertThat(out).isSameAs(input);
    }

    @Test
    public void nullWfFetchUrlShortCircuits() {
        final Op input = serviceOverCustomersSingle("wf-relational:customers");
        final Op out = WfRelationalRewrite.rewrite(input,
                customersFederation(), customersRegistry(), null);

        assertThat(out).isSameAs(input);
    }

    @Test
    public void unknownSourceNameLeftAlone() {
        final Op input = serviceOverCustomersSingle("wf-relational:unknown");
        final Op out = WfRelationalRewrite.rewrite(input,
                FederationRegistry.empty(), customersRegistry(), WF_FETCH_URL);

        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getService().getURI())
                .isEqualTo("wf-relational:unknown");
    }

    /**
     * Federation registry has {@code customers} typed {@code sparql} &mdash;
     * the defensive check refuses to fold. Synthetic configuration; real
     * deployments always align the type + the relational block.
     */
    @Test
    public void wrongSourceTypeLeftAlone() {
        final Op input = serviceOverCustomersSingle("wf-relational:customers");
        final Op out = WfRelationalRewrite.rewrite(input,
                wrongTypeFederation(), customersRegistry(), WF_FETCH_URL);

        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getService().getURI())
                .isEqualTo("wf-relational:customers");
    }

    @Test
    public void serviceNotWfRelationalLeftAlone() {
        // SERVICE <http://example/query> ... — not wf-relational: at all.
        final Op input = serviceOverCustomersSingle("http://example.com/query");
        final Op out = WfRelationalRewrite.rewrite(input,
                customersFederation(), customersRegistry(), WF_FETCH_URL);

        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getService().getURI())
                .isEqualTo("http://example.com/query");
    }
}
