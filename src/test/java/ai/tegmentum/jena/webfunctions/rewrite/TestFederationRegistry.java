package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.ProbeFn;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parity check for the federation registry parser + lookup semantics.
 * Mirrors the design memo at
 * {@code wf-conformance/docs/design/wf-federation.md} &sect;03.
 */
public class TestFederationRegistry {

    private static FederationRegistry parse(final String json) {
        // Let IllegalArgumentException surface untouched so tests can
        // assertThatThrownBy against it directly; only wrap the (rare)
        // atlas.json parse errors so the harness doesn't have to
        // declare a broad throws clause.
        final JsonObject root;
        try {
            root = JSON.parse(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return FederationRegistry.fromJson(root);
    }

    @Test
    public void parsesMemoExampleVerbatim() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {
                      "name": "products",
                      "type": "sparql",
                      "endpoint": "http://oxigraph-products:7878/query",
                      "predicates": ["http://ex/sku", "http://ex/price", "http://ex/label"],
                      "probe_ttl_secs": 3600
                    },
                    {
                      "name": "reviews",
                      "type": "sparql",
                      "endpoint": "http://oxigraph-reviews:7878/query",
                      "predicates": ["http://ex/review_of", "http://ex/rating", "http://ex/reviewer"]
                    },
                    {
                      "name": "manuals-search",
                      "type": "wf-search",
                      "endpoint": "wf-search:manuals",
                      "predicates": []
                    }
                  ]
                }""");
        assertThat(reg.size()).isEqualTo(3);
        assertThat(reg.byName("products").sourceType()).isEqualTo(SourceType.SPARQL);
        assertThat(reg.byName("products").endpoint())
                .isEqualTo("http://oxigraph-products:7878/query");
        assertThat(reg.byName("products").predicates())
                .containsExactly("http://ex/sku", "http://ex/price", "http://ex/label");
        assertThat(reg.byName("products").probeTtlSecs()).hasValue(3600);
        assertThat(reg.byName("reviews").probeTtlSecs()).isEmpty();
        assertThat(reg.byName("manuals-search").sourceType()).isEqualTo(SourceType.WF_SEARCH);
        assertThat(reg.byName("manuals-search").predicates()).isEmpty();
    }

    @Test
    public void byNameLookupResolvesByEntryName() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {"name": "alpha", "type": "sparql", "endpoint": "http://a/", "predicates": []},
                    {"name": "beta",  "type": "http-sparql", "endpoint": "http://b/", "predicates": []}
                  ]
                }""");
        assertThat(reg.byName("alpha")).isNotNull();
        assertThat(reg.byName("alpha").sourceType()).isEqualTo(SourceType.SPARQL);
        assertThat(reg.byName("beta")).isNotNull();
        assertThat(reg.byName("beta").sourceType()).isEqualTo(SourceType.HTTP_SPARQL);
        assertThat(reg.byName("nonexistent")).isNull();
    }

    @Test
    public void findByPredicateReturnsSingletonForUnambiguousSource() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {"name": "products", "type": "sparql", "endpoint": "http://p/",
                     "predicates": ["http://ex/sku", "http://ex/price"]}
                  ]
                }""");
        final List<FederationSource> hits = reg.findByPredicate("http://ex/sku");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).isEqualTo("products");
    }

    /** Multi-source predicate is honestly reported (memo &sect;04 step 2). */
    @Test
    public void findByPredicateReturnsAllSourcesWhenPredicateIsAmbiguous() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {"name": "east", "type": "sparql", "endpoint": "http://east/",
                     "predicates": ["http://ex/label"]},
                    {"name": "west", "type": "sparql", "endpoint": "http://west/",
                     "predicates": ["http://ex/label"]}
                  ]
                }""");
        final List<FederationSource> hits = reg.findByPredicate("http://ex/label");
        assertThat(hits).hasSize(2);
        // Preserves declaration order — matters for stable UNION-branch
        // layout in the federation rewrite.
        assertThat(hits.get(0).name()).isEqualTo("east");
        assertThat(hits.get(1).name()).isEqualTo("west");
    }

    @Test
    public void findByPredicateUnregisteredIsEmpty() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {"name": "products", "type": "sparql", "endpoint": "http://p/",
                     "predicates": ["http://ex/sku"]}
                  ]
                }""");
        assertThat(reg.findByPredicate("http://ex/unknown")).isEmpty();
    }

    @Test
    public void sourcesIteratesInDeclarationOrder() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {"name": "z", "type": "sparql", "endpoint": "http://z/", "predicates": []},
                    {"name": "a", "type": "sparql", "endpoint": "http://a/", "predicates": []}
                  ]
                }""");
        assertThat(reg.sources()).extracting(FederationSource::name)
                .containsExactly("z", "a");
    }

    @Test
    public void acceptsEveryDeclaredSourceType() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [
                    {"name": "a", "type": "sparql",      "endpoint": "http://a/", "predicates": []},
                    {"name": "b", "type": "wf-search",   "endpoint": "wf-search:b", "predicates": []},
                    {"name": "c", "type": "wf-fetch",    "endpoint": "wf-fetch:c",  "predicates": []},
                    {"name": "d", "type": "wf-document", "endpoint": "wf-document:d","predicates": []},
                    {"name": "e", "type": "http-sparql", "endpoint": "http://e/",   "predicates": []}
                  ]
                }""");
        assertThat(reg.byName("a").sourceType()).isEqualTo(SourceType.SPARQL);
        assertThat(reg.byName("b").sourceType()).isEqualTo(SourceType.WF_SEARCH);
        assertThat(reg.byName("c").sourceType()).isEqualTo(SourceType.WF_FETCH);
        assertThat(reg.byName("d").sourceType()).isEqualTo(SourceType.WF_DOCUMENT);
        assertThat(reg.byName("e").sourceType()).isEqualTo(SourceType.HTTP_SPARQL);
    }

    @Test
    public void ofBuildsRegistryFromInMemorySources() {
        final FederationSource one = new FederationSource(
                "one", SourceType.SPARQL, "http://one/",
                List.of("http://ex/p"), OptionalInt.empty());
        final FederationRegistry reg = FederationRegistry.of(List.of(one));
        assertThat(reg.size()).isEqualTo(1);
        assertThat(reg.byName("one")).isSameAs(one);
        assertThat(reg.findByPredicate("http://ex/p")).containsExactly(one);
    }

    @Test
    public void emptyRegistrySemantics() {
        final FederationRegistry reg = FederationRegistry.empty();
        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.size()).isZero();
        assertThat(reg.byName("whatever")).isNull();
        assertThat(reg.findByPredicate("http://ex/anything")).isEmpty();
        assertThat(reg.sources()).isEmpty();
    }

    @Test
    public void rejectsMissingName() {
        assertThatThrownBy(() -> parse("""
                {
                  "sources": [{"type": "sparql", "endpoint": "http://x/", "predicates": []}]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    public void rejectsMissingType() {
        assertThatThrownBy(() -> parse("""
                {
                  "sources": [{"name": "orphan", "endpoint": "http://x/", "predicates": []}]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type")
                .hasMessageContaining("orphan");
    }

    @Test
    public void rejectsMissingEndpoint() {
        assertThatThrownBy(() -> parse("""
                {
                  "sources": [{"name": "urlless", "type": "sparql", "predicates": []}]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint")
                .hasMessageContaining("urlless");
    }

    @Test
    public void rejectsUnknownType() {
        assertThatThrownBy(() -> parse("""
                {
                  "sources": [{"name": "weird", "type": "graphql",
                               "endpoint": "http://x/", "predicates": []}]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown type")
                .hasMessageContaining("graphql")
                .hasMessageContaining("weird");
    }

    @Test
    public void rejectsDuplicateName() {
        assertThatThrownBy(() -> parse("""
                {
                  "sources": [
                    {"name": "dup", "type": "sparql", "endpoint": "http://a/", "predicates": []},
                    {"name": "dup", "type": "sparql", "endpoint": "http://b/", "predicates": []}
                  ]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate name")
                .hasMessageContaining("dup");
    }

    // ---------------------------------------------------------------------
    // v0.2 cost model
    // ---------------------------------------------------------------------

    /**
     * Source-wide {@code cardinality_hint} parses and is applied to
     * every predicate on that source when no per-predicate override is
     * set (memo &sect;07).
     */
    @Test
    public void cardinalityHintParsesAndSurvivesRoundtrip() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [{
                    "name": "products",
                    "type": "sparql",
                    "endpoint": "http://ex/query",
                    "predicates": ["http://ex/sku"],
                    "cardinality_hint": 5000
                  }]
                }""");
        final FederationSource e = reg.byName("products");
        assertThat(e.cardinalityHint()).hasValue(5000L);
        assertThat(e.cardinalityFor("http://ex/sku")).hasValue(5000L);
        assertThat(e.cardinalityFor("http://ex/other")).hasValue(5000L);
    }

    /**
     * Per-predicate cardinality hints override the source-wide default
     * for the matched predicate; other predicates fall back to the
     * source-wide value (memo &sect;07).
     */
    @Test
    public void perPredicateCardinalityHintsOverrideSourceHint() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [{
                    "name": "products",
                    "type": "sparql",
                    "endpoint": "http://ex/query",
                    "predicates": ["http://ex/sku", "http://ex/label"],
                    "cardinality_hint": 5000,
                    "cardinality_hints": {"http://ex/sku": 100}
                  }]
                }""");
        final FederationSource e = reg.byName("products");
        assertThat(e.cardinalityFor("http://ex/sku")).hasValue(100L);
        assertThat(e.cardinalityFor("http://ex/label")).hasValue(5000L);
    }

    /**
     * Neither source-wide nor per-predicate hint set &rarr; empty
     * {@code OptionalLong}. Callers treat this as "unknown, sort last".
     */
    @Test
    public void cardinalityAbsentReturnsEmpty() {
        final FederationRegistry reg = parse("""
                {
                  "sources": [{
                    "name": "products",
                    "type": "sparql",
                    "endpoint": "http://ex/query",
                    "predicates": ["http://ex/sku"]
                  }]
                }""");
        assertThat(reg.byName("products").cardinalityFor("http://ex/sku")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // v0.2 probe mode
    // ---------------------------------------------------------------------

    /**
     * Root-level {@code probe_mode} + {@code probe_ttl_secs} parse into
     * the corresponding registry accessors.
     */
    @Test
    public void probeModeParsesFromJsonRoot() {
        final FederationRegistry reg = parse("""
                {
                  "probe_mode": true,
                  "probe_ttl_secs": 300,
                  "sources": [{
                    "name": "s", "type": "sparql", "endpoint": "http://ex"
                  }]
                }""");
        assertThat(reg.probeMode()).isTrue();
        assertThat(reg.probeTtlSecs()).isEqualTo(300L);
    }

    /**
     * Second probe within the TTL hits the cache and skips a re-probe
     * &mdash; observable by counting invocations on the injected
     * {@link ProbeFn}.
     */
    @Test
    public void probeCacheHitAvoidsReprobe() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final FederationRegistry reg = parse("""
                {
                  "probe_mode": true,
                  "sources": [{
                    "name": "s", "type": "sparql", "endpoint": "http://ex/query"
                  }]
                }""")
                .withProbeFn((src, pred) -> {
                    calls.incrementAndGet();
                    return true;
                });
        final FederationSource s = reg.byName("s");
        assertThat(reg.probePredicate(s, "http://ex/p")).isTrue();
        assertThat(reg.probePredicate(s, "http://ex/p")).isTrue();
        assertThat(calls.get()).as("cache hit must skip re-probe").isEqualTo(1);
    }

    /**
     * With a 0-second TTL every lookup is stale &mdash; the second
     * call, taken past a small sleep, must re-probe.
     */
    @Test
    public void probeCacheTtlExpiryReprobes() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final FederationRegistry reg = parse("""
                {
                  "probe_mode": true,
                  "probe_ttl_secs": 0,
                  "sources": [{
                    "name": "s", "type": "sparql", "endpoint": "http://ex/query"
                  }]
                }""")
                .withProbeFn((src, pred) -> {
                    calls.incrementAndGet();
                    return true;
                });
        final FederationSource s = reg.byName("s");
        reg.probePredicate(s, "http://ex/p");
        // Sleep past the 0-second TTL so elapsed > 0 triggers re-probe.
        Thread.sleep(5);
        reg.probePredicate(s, "http://ex/p");
        assertThat(calls.get()).as("TTL expiry must trigger re-probe").isEqualTo(2);
    }

    /**
     * A probe function that throws surfaces the error to the caller of
     * {@link FederationRegistry#probePredicate}; the higher-level
     * {@link FederationRegistry#findByPredicateProbing} swallows and
     * logs, but the low-level entry point does not.
     */
    @Test
    public void probeEndpointDownSurfacesError() {
        final FederationRegistry reg = parse("""
                {
                  "probe_mode": true,
                  "sources": [{
                    "name": "s", "type": "sparql", "endpoint": "http://ex/query"
                  }]
                }""")
                .withProbeFn((src, pred) -> {
                    throw new RuntimeException("connection refused");
                });
        final FederationSource s = reg.byName("s");
        assertThatThrownBy(() -> reg.probePredicate(s, "http://ex/p"))
                .hasMessageContaining("connection refused");
    }
}
