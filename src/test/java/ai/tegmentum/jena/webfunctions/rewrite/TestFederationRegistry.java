package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.FederationSource;
import ai.tegmentum.jena.webfunctions.rewrite.FederationRegistry.SourceType;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.OptionalInt;

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
}
