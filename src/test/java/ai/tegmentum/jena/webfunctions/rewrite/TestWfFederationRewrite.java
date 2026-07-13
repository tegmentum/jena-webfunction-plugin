package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.graph.Node;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.expr.Expr;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WfFederationRewrite}. Covers the source-
 * selection, same-source grouping, multi-source UNION, filter-pushdown,
 * and pass-through behaviours from design memo &sect;04 of
 * {@code wf-conformance/docs/design/wf-federation.md}.
 */
public class TestWfFederationRewrite {

    // ---------------------------------------------------------------------
    // Registry factories
    // ---------------------------------------------------------------------

    private static FederationSource sparql(final String name,
                                           final String endpoint,
                                           final String... predicates) {
        return new FederationSource(name, SourceType.SPARQL, endpoint,
                List.of(predicates), OptionalInt.empty());
    }

    private static FederationSource wfSearch(final String name,
                                             final String... predicates) {
        return new FederationSource(name, SourceType.WF_SEARCH,
                "wf-search:" + name, List.of(predicates), OptionalInt.empty());
    }

    private static FederationRegistry productsAndReviews() {
        return FederationRegistry.of(List.of(
                sparql("products", "http://oxigraph-products:7878/query",
                        "http://ex/label", "http://ex/price"),
                sparql("reviews", "http://oxigraph-reviews:7878/query",
                        "http://ex/review_of", "http://ex/rating")));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Op parseAlgebra(final String sparql) {
        return Algebra.compile(QueryFactory.create(sparql));
    }

    private static List<OpService> collectServices(final Op op) {
        final List<OpService> out = new ArrayList<>();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(final OpService s) { out.add(s); }
        });
        return out;
    }

    private static List<OpUnion> collectUnions(final Op op) {
        final List<OpUnion> out = new ArrayList<>();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(final OpUnion u) { out.add(u); }
        });
        return out;
    }

    private static List<OpFilter> collectFilters(final Op op) {
        final List<OpFilter> out = new ArrayList<>();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(final OpFilter f) { out.add(f); }
        });
        return out;
    }

    private static OpService serviceForEndpoint(final Op op, final String endpoint) {
        for (OpService s : collectServices(op)) {
            final Node ref = s.getService();
            if (ref != null && ref.isURI() && endpoint.equals(ref.getURI())) {
                return s;
            }
        }
        return null;
    }

    private static int countTriples(final Op op) {
        final int[] count = new int[1];
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(final OpBGP bgp) { count[0] += bgp.getPattern().size(); }
        });
        return count[0];
    }

    // ---------------------------------------------------------------------
    // Assignment tests
    // ---------------------------------------------------------------------

    @Test
    public void unambiguousPredicateGoesToSingleService() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?label WHERE { ?p ex:label ?label }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        // The label triple lives in "products" only.
        final OpService svc = serviceForEndpoint(out, "http://oxigraph-products:7878/query");
        assertThat(svc).as("expected a SERVICE against the products endpoint").isNotNull();
        assertThat(collectServices(out)).hasSize(1);
        assertThat(collectUnions(out)).isEmpty();
    }

    @Test
    public void multiSourcePredicateEmitsUnion() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparql("east", "http://east/query", "http://ex/label"),
                sparql("west", "http://west/query", "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?s ?label WHERE { ?s ex:label ?label }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        assertThat(collectUnions(out))
                .as("multi-source predicate should produce an OpUnion")
                .hasSize(1);
        assertThat(serviceForEndpoint(out, "http://east/query")).isNotNull();
        assertThat(serviceForEndpoint(out, "http://west/query")).isNotNull();
    }

    @Test
    public void sameSourceSharedVarPatternsCollapseIntoOneService() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        // Both ?p ex:label and ?p ex:price live in "products" and share
        // subject var ?p — memo §04 step 3 says they collapse into one
        // SERVICE call.
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?label ?price WHERE {
                  ?p ex:label ?label ; ex:price ?price .
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(1);

        final OpService productsSvc = serviceForEndpoint(out,
                "http://oxigraph-products:7878/query");
        assertThat(productsSvc).isNotNull();
        // Both triples ride inside the single SERVICE body.
        assertThat(countTriples(productsSvc.getSubOp())).isEqualTo(2);
    }

    @Test
    public void crossSourceJoinPreservesOuterJoin() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        // ?p in "products", ?r in "reviews" — different sources, so
        // TWO services and the join stays at the outer level.
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?label ?rating WHERE {
                  ?p ex:label ?label .
                  ?r ex:review_of ?p ; ex:rating ?rating .
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        assertThat(collectServices(out)).hasSize(2);
        assertThat(serviceForEndpoint(out,
                "http://oxigraph-products:7878/query")).isNotNull();
        final OpService reviewsSvc = serviceForEndpoint(out,
                "http://oxigraph-reviews:7878/query");
        assertThat(reviewsSvc).isNotNull();
        // Both review triples (share ?r) collapse inside the reviews service.
        assertThat(countTriples(reviewsSvc.getSubOp())).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Filter pushdown tests
    // ---------------------------------------------------------------------

    @Test
    public void filterOverSingleSourceGetsPushedIntoServiceBody() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?price WHERE {
                  ?p ex:label ?label ; ex:price ?price .
                  FILTER(?price < 50)
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final OpService productsSvc = serviceForEndpoint(out,
                "http://oxigraph-products:7878/query");
        assertThat(productsSvc).isNotNull();

        // The FILTER should live INSIDE the service body, not at the outer level.
        final List<OpFilter> filtersInside = collectFilters(productsSvc.getSubOp());
        assertThat(filtersInside).hasSize(1);
    }

    @Test
    public void filterOverEachSourceGetsPushedIndependently() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        // Memo §05 verbatim example: two filters, one per source.
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?label ?rating WHERE {
                  ?p ex:label ?label ; ex:price ?price .
                  ?r ex:review_of ?p ; ex:rating ?rating .
                  FILTER(?price < 50)
                  FILTER(?rating >= 4)
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final OpService productsSvc = serviceForEndpoint(out,
                "http://oxigraph-products:7878/query");
        final OpService reviewsSvc = serviceForEndpoint(out,
                "http://oxigraph-reviews:7878/query");
        assertThat(productsSvc).isNotNull();
        assertThat(reviewsSvc).isNotNull();

        assertThat(collectFilters(productsSvc.getSubOp())).hasSize(1);
        assertThat(collectFilters(reviewsSvc.getSubOp())).hasSize(1);
    }

    @Test
    public void filterOverCrossSourceVarsStaysAtOuterLevel() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?label ?rating WHERE {
                  ?p ex:label ?label ; ex:price ?price .
                  ?r ex:review_of ?p ; ex:rating ?rating .
                  FILTER(?price < 50 && ?rating >= 4)
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final OpService productsSvc = serviceForEndpoint(out,
                "http://oxigraph-products:7878/query");
        final OpService reviewsSvc = serviceForEndpoint(out,
                "http://oxigraph-reviews:7878/query");
        // Cross-source filter (references both ?price and ?rating) can't
        // be pushed into a single service — it stays at the outer level.
        assertThat(collectFilters(productsSvc.getSubOp())).isEmpty();
        assertThat(collectFilters(reviewsSvc.getSubOp())).isEmpty();

        // But an outer OpFilter still wraps the whole thing.
        boolean anyOuter = false;
        for (OpFilter f : collectFilters(out)) {
            for (Expr e : f.getExprs()) {
                if (e.getVarsMentioned().size() >= 2) { anyOuter = true; break; }
            }
        }
        assertThat(anyOuter).isTrue();
    }

    // ---------------------------------------------------------------------
    // Substrate URL synthesis tests
    // ---------------------------------------------------------------------

    @Test
    public void wfSearchSourceEmitsWfSearchUrl() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                wfSearch("manuals", "http://ex/snippet")));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?snippet WHERE { ?d ex:snippet ?snippet }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        assertThat(serviceForEndpoint(out, "wf-search:manuals"))
                .as("wf-search source should emit a wf-search:<name> SERVICE URL")
                .isNotNull();
    }

    @Test
    public void wfFetchAndWfDocumentSourcesEmitSubstrateUrls() {
        // Sanity check for the two other substrate URL types (memo §06):
        // wf-fetch and wf-document should get their sugar spelling too.
        final FederationRegistry reg = FederationRegistry.of(List.of(
                new FederationSource("fetcher", SourceType.WF_FETCH,
                        "wf-fetch:fetcher", List.of("http://ex/fetched"),
                        OptionalInt.empty()),
                new FederationSource("docs", SourceType.WF_DOCUMENT,
                        "wf-document:docs", List.of("http://ex/hit"),
                        OptionalInt.empty())));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?x ?y WHERE {
                  ?a ex:fetched ?x .
                  ?b ex:hit ?y .
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        assertThat(serviceForEndpoint(out, "wf-fetch:fetcher")).isNotNull();
        assertThat(serviceForEndpoint(out, "wf-document:docs")).isNotNull();
    }

    @Test
    public void sameSourceDisjointVarsStayAsSeparateServices() {
        // Memo §04 step 3 groups by SHARED subject/object var. Two
        // same-source triples with no var overlap don't merge — they
        // each get their own SERVICE (correct: no local reason to fold
        // them together at the algebra level).
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?a ?b ?labelA ?labelB WHERE {
                  ?a ex:label ?labelA .
                  ?b ex:label ?labelB .
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(2);
        // Both hit the same endpoint but they're independent SERVICE
        // clauses (each carries one triple).
        for (OpService s : services) {
            assertThat(s.getService().getURI())
                    .isEqualTo("http://oxigraph-products:7878/query");
            assertThat(countTriples(s.getSubOp())).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------------
    // Pass-through tests
    // ---------------------------------------------------------------------

    @Test
    public void unregisteredPredicateStaysAsPlainBgp() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        // ex:unknown isn't declared anywhere.
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?s ?v WHERE { ?s ex:unknown ?v }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        // No SERVICE was synthesised; the triple survives inside an OpBGP.
        assertThat(collectServices(out)).isEmpty();
        assertThat(countTriples(out)).isEqualTo(1);
    }

    @Test
    public void mixedBgpKeepsUnregisteredTripleLocal() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?s ?label ?v WHERE {
                  ?s ex:label ?label .
                  ?s ex:unknown ?v .
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        // ex:label routes to products; ex:unknown stays local.
        assertThat(collectServices(out)).hasSize(1);
        final OpService svc = serviceForEndpoint(out,
                "http://oxigraph-products:7878/query");
        assertThat(svc).isNotNull();
        assertThat(countTriples(svc.getSubOp())).isEqualTo(1);
        // The unknown triple survives at the outer join level.
        assertThat(countTriples(out)).isEqualTo(2);
    }

    @Test
    public void explicitServiceIsLeftUntouched() {
        final FederationRegistry reg = productsAndReviews();
        final InvokeRegistry inv = new InvokeRegistry();
        // Caller opted into a specific endpoint via SERVICE — the pass
        // must not rewrite the inner BGP even though ex:label matches
        // a registered source.
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?label WHERE {
                  SERVICE <http://elsewhere/query> {
                    ?p ex:label ?label .
                  }
                }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        // Only the caller's SERVICE survives; no synthesized one.
        final List<OpService> services = collectServices(out);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getService().getURI()).isEqualTo("http://elsewhere/query");
    }

    // ---------------------------------------------------------------------
    // SILENT resolution (memo §08)
    // ---------------------------------------------------------------------

    private static FederationSource sparqlWithSilent(final String name,
                                                     final String endpoint,
                                                     final Optional<Boolean> silent,
                                                     final String... predicates) {
        return new FederationSource(name, SourceType.SPARQL, endpoint,
                List.of(predicates), OptionalInt.empty(), silent);
    }

    private static FederationSource wfSearchWithSilent(final String name,
                                                       final Optional<Boolean> silent,
                                                       final String... predicates) {
        return new FederationSource(name, SourceType.WF_SEARCH,
                "wf-search:" + name, List.of(predicates), OptionalInt.empty(),
                silent);
    }

    /**
     * Explicit {@code silent: true} on a SPARQL entry emits
     * {@code SERVICE SILENT} verbatim.
     */
    @Test
    public void federationSourceSilentTrueEmitsSilentService() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithSilent("silent_products",
                        "http://silent-products/query",
                        Optional.of(true),
                        "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?l WHERE { ?p ex:label ?l }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final OpService svc = serviceForEndpoint(out, "http://silent-products/query");
        assertThat(svc).as("products SERVICE present").isNotNull();
        assertThat(svc.getSilent())
                .as("explicit silent:true must emit SERVICE SILENT")
                .isTrue();
    }

    /**
     * Explicit {@code silent: false} on a SPARQL entry overrides the
     * per-type default (which would otherwise be {@code true}).
     */
    @Test
    public void federationSourceSilentFalseEmitsNonSilentService() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithSilent("loud_products",
                        "http://loud-products/query",
                        Optional.of(false),
                        "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?l WHERE { ?p ex:label ?l }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final OpService svc = serviceForEndpoint(out, "http://loud-products/query");
        assertThat(svc).as("products SERVICE present").isNotNull();
        assertThat(svc.getSilent())
                .as("explicit silent:false must suppress SERVICE SILENT")
                .isFalse();
    }

    /**
     * Omitted {@code silent} on a SPARQL source falls back to the
     * type-based default (true — network endpoint, no probing in static
     * mode).
     */
    @Test
    public void federationSourceSilentDefaultsTrueForSparql() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                sparqlWithSilent("default_products",
                        "http://default-products/query",
                        Optional.empty(),
                        "http://ex/label")));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?l WHERE { ?p ex:label ?l }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final OpService svc = serviceForEndpoint(out, "http://default-products/query");
        assertThat(svc).as("products SERVICE present").isNotNull();
        assertThat(svc.getSilent())
                .as("SPARQL sources default to SILENT when `silent` is omitted")
                .isTrue();
    }

    /**
     * Omitted {@code silent} on a wf-search source falls back to the
     * type-based default (false — substrate-local dispatch; failures are
     * bugs the operator needs to see, not network flaps to mask).
     */
    @Test
    public void federationSourceSilentDefaultsFalseForWfSearch() {
        final FederationRegistry reg = FederationRegistry.of(List.of(
                wfSearchWithSilent("manuals",
                        Optional.empty(),
                        "http://ex/has_manual")));
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?p ?m WHERE { ?p ex:has_manual ?m }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);

        final OpService svc = serviceForEndpoint(out, "wf-search:manuals");
        assertThat(svc).as("wf-search SERVICE present").isNotNull();
        assertThat(svc.getSilent())
                .as("wf-search sources default to non-SILENT when `silent` is omitted")
                .isFalse();
    }

    @Test
    public void emptyRegistryIsNoop() {
        final FederationRegistry reg = FederationRegistry.empty();
        final InvokeRegistry inv = new InvokeRegistry();
        final Op input = parseAlgebra("""
                PREFIX ex: <http://ex/>
                SELECT ?s ?label WHERE { ?s ex:label ?label }""");
        final Op out = WfFederationRewrite.rewrite(input, reg, inv);
        // Empty registry short-circuits — output is the exact same
        // reference the caller passed in.
        assertThat(out).isSameAs(input);
    }
}
