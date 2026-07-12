package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.FulltextRegistry.FulltextIndex;
import ai.tegmentum.jena.webfunctions.rewrite.FulltextRegistry.FulltextMode;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parity check for the fulltext registry parser + lookup semantics.
 * Mirrors {@code oxigraph-wf/src/fulltext_registry.rs::tests} and its
 * RDF4J port at {@code TestFulltextRegistry} in that sibling repo.
 */
public class TestFulltextRegistry {

    private static FulltextRegistry parse(final String json) {
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
        return FulltextRegistry.fromJson(root);
    }

    @Test
    public void parsesMinimalLiteralIndexEntry() {
        final FulltextRegistry reg = parse("""
                {
                  "indexes": [{
                    "name": "products",
                    "mode": "literal-index",
                    "backend_url": "file:///opt/wf_fulltext.wasm",
                    "predicates": ["http://ex/label"],
                    "opts": {"index": "products"}
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final FulltextIndex e = reg.byName("products");
        assertThat(e).isNotNull();
        assertThat(e.mode()).isEqualTo(FulltextMode.LITERAL_INDEX);
        assertThat(e.backendUrl()).isEqualTo("file:///opt/wf_fulltext.wasm");
        assertThat(e.predicates()).containsExactly("http://ex/label");
        assertThat(e.optsJson()).contains("index").contains("products");
        assertThat(e.languages()).isEmpty();
        assertThat(e.sweepIntervalSecs()).isEmpty();
    }

    /** Design memo &sect;06's verbatim two-entry example, ported. */
    @Test
    public void parsesTwoEntryMemoExampleVerbatim() {
        final FulltextRegistry reg = parse("""
                {
                  "indexes": [
                    {
                      "name": "products",
                      "mode": "literal-index",
                      "predicates": ["http://ex/label", "http://ex/description"],
                      "backend_url": "file:///.../wf_fulltext.wasm",
                      "opts": { "index": "products" },
                      "languages": ["en", "de"],
                      "sweep_interval_secs": 300
                    },
                    {
                      "name": "manuals",
                      "mode": "document-corpus",
                      "backend_url": "file:///.../wf_fulltext.wasm",
                      "opts": { "index": "manuals" }
                    }
                  ]
                }""");
        assertThat(reg.size()).isEqualTo(2);

        final FulltextIndex products = reg.byName("products");
        assertThat(products.mode()).isEqualTo(FulltextMode.LITERAL_INDEX);
        assertThat(products.predicates())
                .containsExactly("http://ex/label", "http://ex/description");
        assertThat(products.languages()).containsExactly("en", "de");
        assertThat(products.sweepIntervalSecs()).hasValue(300);

        final FulltextIndex manuals = reg.byName("manuals");
        assertThat(manuals.mode()).isEqualTo(FulltextMode.DOCUMENT_CORPUS);
        assertThat(manuals.predicates()).isEmpty();
        assertThat(manuals.languages()).isEmpty();
        assertThat(manuals.sweepIntervalSecs()).isEmpty();

        // Iteration surface: literalIndexEntries filters DOCUMENT_CORPUS.
        final List<String> literalNames = reg.literalIndexEntries().stream()
                .map(FulltextIndex::name)
                .toList();
        assertThat(literalNames).containsExactly("products");
    }

    /**
     * The contract per &sect;06: only literal-index entries participate in
     * filter-fold. DOCUMENT_CORPUS entries listed first must not shadow
     * a later literal-index entry that owns the predicate.
     */
    @Test
    public void findByPredicateSkipsDocumentCorpus() {
        final FulltextRegistry reg = parse("""
                {
                  "indexes": [
                    {
                      "name": "products",
                      "mode": "document-corpus",
                      "backend_url": "file:///x.wasm",
                      "opts": {"index": "products"}
                    },
                    {
                      "name": "labels",
                      "mode": "literal-index",
                      "backend_url": "file:///x.wasm",
                      "predicates": ["http://ex/label"],
                      "opts": {"index": "products"}
                    }
                  ]
                }""");
        final FulltextIndex hit = reg.findByPredicate("http://ex/label");
        assertThat(hit).isNotNull();
        assertThat(hit.name()).isEqualTo("labels");
        assertThat(hit.mode()).isEqualTo(FulltextMode.LITERAL_INDEX);

        assertThat(reg.findByPredicate("http://ex/notregistered")).isNull();
    }

    @Test
    public void rejectsLiteralIndexWithEmptyPredicates() {
        assertThatThrownBy(() -> parse("""
                {
                  "indexes": [{
                    "name": "bad",
                    "mode": "literal-index",
                    "backend_url": "file:///x.wasm",
                    "opts": {"index": "bad"}
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("literal-index entries must")
                .hasMessageContaining("bad");
    }

    @Test
    public void rejectsDocumentCorpusWithNonEmptyPredicates() {
        assertThatThrownBy(() -> parse("""
                {
                  "indexes": [{
                    "name": "leaky",
                    "mode": "document-corpus",
                    "backend_url": "file:///x.wasm",
                    "predicates": ["http://ex/label"],
                    "opts": {"index": "leaky"}
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document-corpus entries")
                .hasMessageContaining("leaky");
    }

    @Test
    public void rejectsUnknownMode() {
        assertThatThrownBy(() -> parse("""
                {
                  "indexes": [{
                    "name": "weird",
                    "mode": "hybrid",
                    "backend_url": "file:///x.wasm",
                    "opts": {"index": "weird"}
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown mode")
                .hasMessageContaining("hybrid")
                .hasMessageContaining("weird");
    }

    @Test
    public void rejectsMissingName() {
        assertThatThrownBy(() -> parse("""
                {
                  "indexes": [{
                    "mode": "literal-index",
                    "backend_url": "file:///x.wasm",
                    "predicates": ["http://ex/p"],
                    "opts": {}
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    public void rejectsMissingMode() {
        assertThatThrownBy(() -> parse("""
                {
                  "indexes": [{
                    "name": "orphan",
                    "backend_url": "file:///x.wasm",
                    "opts": {}
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode")
                .hasMessageContaining("orphan");
    }

    @Test
    public void rejectsMissingBackendUrl() {
        assertThatThrownBy(() -> parse("""
                {
                  "indexes": [{
                    "name": "urlless",
                    "mode": "literal-index",
                    "predicates": ["http://ex/p"],
                    "opts": {}
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backend_url")
                .hasMessageContaining("urlless");
    }

    @Test
    public void emptyRegistrySemantics() {
        final FulltextRegistry reg = FulltextRegistry.empty();
        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.size()).isZero();
        assertThat(reg.findByPredicate("http://ex/anything")).isNull();
        assertThat(reg.literalIndexEntries()).isEmpty();
        assertThat(reg.byName("whatever")).isNull();
    }

    /**
     * Even if the caller supplies languages/sweep_interval_secs on a
     * DOCUMENT_CORPUS entry (they're meaningless there), we normalize
     * them out so downstream code doesn't have to re-check mode.
     */
    @Test
    public void documentCorpusStripsLanguagesAndSweepInterval() {
        final FulltextRegistry reg = parse("""
                {
                  "indexes": [{
                    "name": "docs",
                    "mode": "document-corpus",
                    "backend_url": "file:///x.wasm",
                    "opts": {"index": "docs", "backend": "manticore"},
                    "languages": ["en"],
                    "sweep_interval_secs": 42
                  }]
                }""");
        final FulltextIndex e = reg.byName("docs");
        assertThat(e.languages()).isEmpty();
        assertThat(e.sweepIntervalSecs()).isEmpty();
        assertThat(e.optsJson()).contains("manticore");
    }
}
