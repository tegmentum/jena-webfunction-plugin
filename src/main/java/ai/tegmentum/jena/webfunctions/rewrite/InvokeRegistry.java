package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.graph.Node;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-server registry mapping opaque ids to {@link InvokeSpec}s.
 * Populated by the partial-application rewrite (constant-fold pass on
 * {@code wf:partial(...)}), drained by the {@code wf-invoke:<id>}
 * SERVICE handler.
 *
 * <p>Mirrors {@code oxigraph-wf/src/partial.rs::InvokeRegistry}. One-shot
 * semantics keep the registry bounded: {@link #take(long)} removes the
 * entry on lookup so a completed SERVICE call frees its config.
 */
public final class InvokeRegistry {

    public static final String WF_PARTIAL_IRI =
            "http://tegmentum.ai/ns/webfunction/partial";
    public static final String WF_INVOKE_SCHEME = "wf-invoke:";

    /** Snapshot of a partial-application call: wasm URL + positional args. */
    public static final class InvokeSpec {
        public final String wasmUrl;
        public final List<Node> args;

        public InvokeSpec(final String wasmUrl, final List<Node> args) {
            this.wasmUrl = wasmUrl;
            this.args = Collections.unmodifiableList(args);
        }
    }

    private final Map<Long, InvokeSpec> inner = new HashMap<>();
    private final AtomicLong next = new AtomicLong(0);

    public InvokeRegistry() {}

    public long insert(final InvokeSpec spec) {
        final long id = next.getAndIncrement();
        synchronized (inner) {
            inner.put(id, spec);
        }
        return id;
    }

    public Optional<InvokeSpec> take(final long id) {
        synchronized (inner) {
            return Optional.ofNullable(inner.remove(id));
        }
    }

    public int size() {
        synchronized (inner) {
            return inner.size();
        }
    }

    /**
     * Return the IRI string for a given registry id. The
     * {@code wf-invoke:} URI is opaque; the id sits in the path as a
     * lowercase hex integer, matching the Rust prototype.
     */
    public static String iriFor(final long id) {
        return String.format(Locale.ROOT, "%s%x", WF_INVOKE_SCHEME, id);
    }

    /** Parse a {@code wf-invoke:<hex>} IRI back into a registry id. */
    public static Optional<Long> idFromIri(final String iri) {
        if (!iri.startsWith(WF_INVOKE_SCHEME)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseUnsignedLong(iri.substring(WF_INVOKE_SCHEME.length()), 16));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
