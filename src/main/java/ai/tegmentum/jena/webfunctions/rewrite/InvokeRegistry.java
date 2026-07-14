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
        /**
         * Optional caller override for the guest's exported function name.
         * When null the dispatcher auto-detects: prefer {@code evaluate}
         * (substrate-wide default), then fall back to a single top-level
         * function export. See
         * {@link ai.tegmentum.jena.webfunctions.JenaWasmInstance#resolveEntryPoint(String)}.
         */
        public final String entryPoint;
        /**
         * Rewrite-time projection map from guest-emitted column name
         * ({@code doc}, {@code snippet}, ...) to outer-query variable name.
         * Populated by rewrite passes that inspect the SERVICE body's
         * {@code ?_ wf:<col> ?var} triples BEFORE Jena's per-binding
         * substitution rewrites them away.
         *
         * <p>{@link ai.tegmentum.jena.webfunctions.WfInvokeService} prefers
         * this map when non-empty; when empty (or on the pre-projection
         * path), the executor falls back to walking the SERVICE body it
         * receives at dispatch time. The fallback is broken for any
         * {@code wf:<col> ?var} whose {@code ?var} is bound by the outer
         * scope — Jena substitutes the outer value into the SERVICE body
         * before {@code createExecution} sees it, so the executor scan
         * only finds the still-free {@code wf:<col> ?var} triples and
         * silently drops the substituted ones. That gap collapses the
         * outer join to a Cartesian product (federation_wf_search
         * regression).
         *
         * <p>Mirrors the Rust QLever fix
         * ({@code qlever-wf-runtime::wf_search_rewrite} commit
         * {@code 04fdb03}), which threads the same map through
         * {@code InvokeSpec.projection}.
         *
         * <p>Insertion-ordered so declaration order is preserved for
         * reproducible debug output.
         */
        public final Map<String, String> projection;

        public InvokeSpec(final String wasmUrl, final List<Node> args) {
            this(wasmUrl, args, null, Map.of());
        }

        public InvokeSpec(final String wasmUrl, final List<Node> args, final String entryPoint) {
            this(wasmUrl, args, entryPoint, Map.of());
        }

        public InvokeSpec(final String wasmUrl, final List<Node> args, final String entryPoint,
                          final Map<String, String> projection) {
            this.wasmUrl = wasmUrl;
            this.args = Collections.unmodifiableList(args);
            this.entryPoint = entryPoint;
            this.projection = projection == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(projection));
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

    /**
     * Non-destructive lookup: read a spec without removing it. Mirrors the
     * peek variant of the Rust {@code pop_by_id} accessor. Jena's SERVICE
     * executor is invoked once per outer binding — a destructive
     * {@link #take(long)} would fail the second call — so the
     * {@code wf-invoke:} service dispatcher uses this accessor to preserve
     * the entry across re-entrance.
     */
    public Optional<InvokeSpec> get(final long id) {
        synchronized (inner) {
            return Optional.ofNullable(inner.get(id));
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
