package ai.tegmentum.jena.webfunctions.rewrite;

import ai.tegmentum.jena.webfunctions.rewrite.DocumentRegistry.DocumentIndex;
import ai.tegmentum.jena.webfunctions.rewrite.DocumentRegistry.DocumentMode;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parity check for the document registry parser + lookup semantics.
 * Mirrors the design memo at {@code wf-conformance/docs/design/wf-document.md}
 * &sect;07 and the RDF4J/Oxigraph siblings.
 */
public class TestDocumentRegistry {

    private static DocumentRegistry parse(final String json) {
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
        return DocumentRegistry.fromJson(root);
    }

    @Test
    public void parsesMinimalManagedEntry() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "manuals",
                    "mode": "managed",
                    "guest_url": "file:///opt/wf_document.wasm",
                    "search_backend": "http://localhost:9308",
                    "storage_backend": "http://localhost:8080",
                    "search_index": "manuals",
                    "sirix_database": "docs",
                    "sirix_resource": "manuals",
                    "sweep_interval_secs": 300,
                    "revision_retention": "latest"
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final DocumentIndex e = reg.byName("manuals");
        assertThat(e).isNotNull();
        assertThat(e.mode()).isEqualTo(DocumentMode.MANAGED);
        assertThat(e.guestUrl()).isEqualTo("file:///opt/wf_document.wasm");
        assertThat(e.searchBackend()).isEqualTo("http://localhost:9308");
        assertThat(e.storageBackend()).isEqualTo("http://localhost:8080");
        assertThat(e.searchIndex()).isEqualTo("manuals");
        assertThat(e.sirixDatabase()).isEqualTo("docs");
        assertThat(e.sirixResource()).isEqualTo("manuals");
        assertThat(e.sweepIntervalSecs()).hasValue(300);
        assertThat(e.revisionRetention()).isEqualTo("latest");
        // opts omitted -> canonical `{}` fallback
        assertThat(e.optsJson()).isEqualTo("{}");
    }

    @Test
    public void parsesMinimalFederatedEntry() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "external",
                    "mode": "federated",
                    "guest_url": "file:///opt/wf_document.wasm",
                    "search_backend": "http://search.example/",
                    "storage_backend": "http://storage.example/",
                    "search_index": "kb",
                    "sirix_database": "kb",
                    "sirix_resource": "kb"
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final DocumentIndex e = reg.byName("external");
        assertThat(e).isNotNull();
        assertThat(e.mode()).isEqualTo(DocumentMode.FEDERATED);
        // Federated normalizes sweep + revision-retention to absent/empty
        // so downstream code can rely on the struct-level invariant
        // without re-checking mode.
        assertThat(e.sweepIntervalSecs()).isEmpty();
        assertThat(e.revisionRetention()).isEmpty();
    }

    /** Design memo &sect;07's verbatim example. */
    @Test
    public void parsesMemoExampleVerbatim() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "manuals",
                    "mode": "managed",
                    "guest_url": "file:///…/wf_document.wasm",
                    "search_backend": "http://localhost:9308",
                    "storage_backend": "http://localhost:8080",
                    "search_index": "manuals",
                    "sirix_database": "docs",
                    "sirix_resource": "manuals",
                    "sweep_interval_secs": 300,
                    "revision_retention": "latest"
                  }]
                }""");
        assertThat(reg.size()).isEqualTo(1);
        final DocumentIndex e = reg.byName("manuals");
        assertThat(e.mode()).isEqualTo(DocumentMode.MANAGED);
        assertThat(e.guestUrl()).isEqualTo("file:///…/wf_document.wasm");
        assertThat(e.searchBackend()).isEqualTo("http://localhost:9308");
        assertThat(e.storageBackend()).isEqualTo("http://localhost:8080");
        assertThat(e.searchIndex()).isEqualTo("manuals");
        assertThat(e.sirixDatabase()).isEqualTo("docs");
        assertThat(e.sirixResource()).isEqualTo("manuals");
        assertThat(e.sweepIntervalSecs()).hasValue(300);
        assertThat(e.revisionRetention()).isEqualTo("latest");
    }

    @Test
    public void byNameLookupResolvesByEntryName() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [
                    {
                      "name": "alpha",
                      "mode": "managed",
                      "guest_url": "file:///a.wasm",
                      "search_backend": "http://a.search/",
                      "storage_backend": "http://a.store/",
                      "search_index": "alpha",
                      "sirix_database": "a",
                      "sirix_resource": "alpha",
                      "sweep_interval_secs": 60,
                      "revision_retention": "latest"
                    },
                    {
                      "name": "beta",
                      "mode": "federated",
                      "guest_url": "file:///b.wasm",
                      "search_backend": "http://b.search/",
                      "storage_backend": "http://b.store/",
                      "search_index": "beta",
                      "sirix_database": "b",
                      "sirix_resource": "beta"
                    }
                  ]
                }""");
        assertThat(reg.byName("alpha")).isNotNull();
        assertThat(reg.byName("alpha").mode()).isEqualTo(DocumentMode.MANAGED);
        assertThat(reg.byName("beta")).isNotNull();
        assertThat(reg.byName("beta").mode()).isEqualTo(DocumentMode.FEDERATED);
        assertThat(reg.byName("nonexistent")).isNull();
    }

    /**
     * The contract per &sect;07: only managed entries participate in
     * the periodic sweep. {@link DocumentRegistry#managedEntries()}
     * must filter federated entries out.
     */
    @Test
    public void managedEntriesSkipsFederated() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [
                    {
                      "name": "outside",
                      "mode": "federated",
                      "guest_url": "file:///x.wasm",
                      "search_backend": "http://x.search/",
                      "storage_backend": "http://x.store/",
                      "search_index": "outside",
                      "sirix_database": "x",
                      "sirix_resource": "outside"
                    },
                    {
                      "name": "inside",
                      "mode": "managed",
                      "guest_url": "file:///x.wasm",
                      "search_backend": "http://x.search/",
                      "storage_backend": "http://x.store/",
                      "search_index": "inside",
                      "sirix_database": "x",
                      "sirix_resource": "inside",
                      "sweep_interval_secs": 120,
                      "revision_retention": "latest"
                    }
                  ]
                }""");
        final List<String> managedNames = reg.managedEntries().stream()
                .map(DocumentIndex::name)
                .toList();
        assertThat(managedNames).containsExactly("inside");
        assertThat(reg.entries()).hasSize(2);
    }

    @Test
    public void rejectsManagedWithoutSweepIntervalSecs() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "bad",
                    "mode": "managed",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "bad",
                    "sirix_database": "d",
                    "sirix_resource": "bad",
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managed entries must")
                .hasMessageContaining("sweep_interval_secs")
                .hasMessageContaining("bad");
    }

    @Test
    public void rejectsManagedWithoutRevisionRetention() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "bad",
                    "mode": "managed",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "bad",
                    "sirix_database": "d",
                    "sirix_resource": "bad",
                    "sweep_interval_secs": 300
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managed entries must")
                .hasMessageContaining("revision_retention")
                .hasMessageContaining("bad");
    }

    /**
     * v1.0 lifts the v0.2 gate on {@code revision_retention: "all"} —
     * time-travel search is now supported end-to-end (guest, sweep,
     * URL sugar), so the parser accepts it.
     */
    @Test
    public void acceptsRevisionRetentionAll() {
        final DocumentRegistry reg = parse("""
                {
                  "documents": [{
                    "name": "manuals",
                    "mode": "managed",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "manuals",
                    "sirix_database": "d",
                    "sirix_resource": "manuals",
                    "sweep_interval_secs": 300,
                    "revision_retention": "all"
                  }]
                }""");
        final DocumentIndex e = reg.byName("manuals");
        assertThat(e).isNotNull();
        assertThat(e.mode()).isEqualTo(DocumentMode.MANAGED);
        assertThat(e.revisionRetention()).isEqualTo("all");
    }

    @Test
    public void rejectsUnknownRevisionRetention() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "bad",
                    "mode": "managed",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "bad",
                    "sirix_database": "d",
                    "sirix_resource": "bad",
                    "sweep_interval_secs": 300,
                    "revision_retention": "some-day-maybe"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision_retention")
                .hasMessageContaining("some-day-maybe")
                .hasMessageContaining("bad");
    }

    @Test
    public void rejectsUnknownMode() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "weird",
                    "mode": "hybrid",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "weird",
                    "sirix_database": "d",
                    "sirix_resource": "weird"
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
                  "documents": [{
                    "mode": "managed",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "n",
                    "sirix_database": "d",
                    "sirix_resource": "r",
                    "sweep_interval_secs": 300,
                    "revision_retention": "latest"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    public void rejectsMissingMode() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "orphan",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "n",
                    "sirix_database": "d",
                    "sirix_resource": "r"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode")
                .hasMessageContaining("orphan");
    }

    @Test
    public void rejectsMissingGuestUrl() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "urlless",
                    "mode": "federated",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "n",
                    "sirix_database": "d",
                    "sirix_resource": "r"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guest_url")
                .hasMessageContaining("urlless");
    }

    @Test
    public void rejectsMissingSearchBackend() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nosearch",
                    "mode": "federated",
                    "guest_url": "file:///x.wasm",
                    "storage_backend": "http://t/",
                    "search_index": "n",
                    "sirix_database": "d",
                    "sirix_resource": "r"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search_backend")
                .hasMessageContaining("nosearch");
    }

    @Test
    public void rejectsMissingStorageBackend() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nostorage",
                    "mode": "federated",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "search_index": "n",
                    "sirix_database": "d",
                    "sirix_resource": "r"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage_backend")
                .hasMessageContaining("nostorage");
    }

    @Test
    public void rejectsMissingSearchIndex() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "noindex",
                    "mode": "federated",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "sirix_database": "d",
                    "sirix_resource": "r"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search_index")
                .hasMessageContaining("noindex");
    }

    @Test
    public void rejectsMissingSirixDatabase() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nodb",
                    "mode": "federated",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "n",
                    "sirix_resource": "r"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sirix_database")
                .hasMessageContaining("nodb");
    }

    @Test
    public void rejectsMissingSirixResource() {
        assertThatThrownBy(() -> parse("""
                {
                  "documents": [{
                    "name": "nores",
                    "mode": "federated",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "n",
                    "sirix_database": "d"
                  }]
                }"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sirix_resource")
                .hasMessageContaining("nores");
    }

    @Test
    public void emptyRegistrySemantics() {
        final DocumentRegistry reg = DocumentRegistry.empty();
        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.size()).isZero();
        assertThat(reg.byName("whatever")).isNull();
        assertThat(reg.managedEntries()).isEmpty();
        assertThat(reg.entries()).isEmpty();
    }

    @Test
    public void defaultSweepIntervalConstantMatchesMemo() {
        // The memo &sect;08 cites 300s as the freshness contract for
        // managed mode; this constant documents that default for
        // programmatic entry construction.
        assertThat(DocumentRegistry.DEFAULT_SWEEP_INTERVAL_SECS).isEqualTo(300);
    }

    // -----------------------------------------------------------------
    // v1.0 window / tail retention policies (memo `wf-document-v1.md` §03)
    // -----------------------------------------------------------------

    private static String managedWithRetention(final String retentionJson) {
        return """
                {
                  "documents": [{
                    "name": "manuals",
                    "mode": "managed",
                    "guest_url": "file:///x.wasm",
                    "search_backend": "http://s/",
                    "storage_backend": "http://t/",
                    "search_index": "manuals",
                    "sirix_database": "d",
                    "sirix_resource": "manuals",
                    "sweep_interval_secs": 300,
                    "revision_retention": %s
                  }]
                }
                """.formatted(retentionJson);
    }

    @Test
    public void acceptsWindowDays() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"window\": \"30d\"}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("window:30d");
    }

    @Test
    public void acceptsWindowHours() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"window\": \"24h\"}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("window:24h");
    }

    @Test
    public void acceptsWindowMinutes() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"window\": \"5m\"}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("window:5m");
    }

    @Test
    public void acceptsTailPositive() {
        final DocumentRegistry reg = parse(managedWithRetention("{\"tail\": 10}"));
        assertThat(reg.byName("manuals").revisionRetention()).isEqualTo("tail:10");
    }

    @Test
    public void rejectsWindowBadUnit() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"window\": \"30x\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown unit")
                .hasMessageContaining("30x")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsWindowEmpty() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"window\": \"\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsTailZero() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"tail\": 0}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsTailNegative() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"tail\": -1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer")
                .hasMessageContaining("manuals");
    }

    @Test
    public void rejectsUnknownDiscriminant() {
        assertThatThrownBy(() -> parse(managedWithRetention("{\"unknown\": \"value\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown revision_retention discriminant")
                .hasMessageContaining("unknown")
                .hasMessageContaining("manuals");
    }
}
