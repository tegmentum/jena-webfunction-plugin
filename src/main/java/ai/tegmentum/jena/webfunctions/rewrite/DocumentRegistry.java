package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Planner-side catalog of registered document indexes. Two-mode
 * design, mirroring the design memo at
 * {@code wf-conformance/docs/design/wf-document.md} &sect;03 and &sect;07:
 *
 * <ul>
 *   <li><b>{@link DocumentMode#MANAGED}</b> &mdash; the substrate owns
 *       both the search backend (Manticore) and the document store
 *       (SirixDB). Ingest goes into Sirix; the periodic sweep keeps
 *       Manticore mirroring Sirix's committed documents. Managed
 *       entries carry sweep + revision-retention config.</li>
 *   <li><b>{@link DocumentMode#FEDERATED}</b> &mdash; the substrate is a
 *       pure client of both stores. Neither is owned; no sweep. Fetch
 *       works iff the storage backend serves documents keyed by the
 *       same id the search backend returns. Sweep and revision-retention
 *       fields are meaningless and normalized to absent/empty.</li>
 * </ul>
 *
 * <p>Read-only after load; an empty registry (no config flag) is a
 * valid state and is what every consumer treats as an unconditional
 * no-op. Identical lifecycle to {@link FulltextRegistry}.
 *
 * <p>Companion to {@link FulltextRegistry}. As of v1.0 the registry is
 * consulted by the {@link WfSearchRewrite} pass, which expands
 * {@code SERVICE <wf-search:name[@time][?opts]>} into a
 * {@code wf-invoke:} allocation with the entry's config baked in.
 *
 * <p>Java port of the design memo's registry shape; sibling of
 * {@code oxigraph-wf/src/document_registry.rs}.
 */
public final class DocumentRegistry {

    /**
     * Default sweep cadence when an entry omits {@code sweep_interval_secs}.
     * Matches &sect;08 of the memo: the freshness contract of managed mode
     * is that documents become findable at t={@code sweep_interval_secs}
     * after being committed to Sirix, with 300s being the memo-cited
     * example. Managed entries are required to declare the value
     * explicitly at load time; this constant documents the recommended
     * default for callers building entries programmatically.
     */
    public static final int DEFAULT_SWEEP_INTERVAL_SECS = 300;

    /** Which of the two modes from &sect;03 of the memo an entry is in. */
    public enum DocumentMode {
        /**
         * Substrate owns both backends. Periodic sweep mirrors Sirix's
         * committed documents into the search backend. Requires
         * {@code sweep_interval_secs} + {@code revision_retention}.
         */
        MANAGED,
        /**
         * Substrate is a client. No sweep; both stores are populated
         * out-of-band. Sweep / revision-retention fields are stripped
         * during normalization.
         */
        FEDERATED
    }

    private final List<DocumentIndex> entries;
    /** Name &rarr; index-in-{@code entries} for administrative lookup. */
    private final Map<String, Integer> nameToEntry;

    private DocumentRegistry(final List<DocumentIndex> entries,
                             final Map<String, Integer> nameToEntry) {
        this.entries = List.copyOf(entries);
        this.nameToEntry = Map.copyOf(nameToEntry);
    }

    /**
     * Empty registry &mdash; the uninstrumented-startup state. Every
     * lookup is a no-op; {@link #isEmpty()} returns true.
     */
    public static DocumentRegistry empty() {
        return new DocumentRegistry(List.of(), Map.of());
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size()        { return entries.size(); }

    /** Administrative lookup &mdash; resolve an entry by its {@code name} field. */
    public DocumentIndex byName(final String name) {
        final Integer idx = nameToEntry.get(name);
        return idx == null ? null : entries.get(idx);
    }

    /**
     * Iterate every {@link DocumentMode#MANAGED} entry. Used by the
     * periodic sweep to enumerate what needs mirroring from the storage
     * backend into the search backend.
     */
    public List<DocumentIndex> managedEntries() {
        final List<DocumentIndex> out = new ArrayList<>();
        for (DocumentIndex e : entries) {
            if (e.mode() == DocumentMode.MANAGED) out.add(e);
        }
        return out;
    }

    /**
     * All registered entries, regardless of mode. Used by callers that
     * need the full set (e.g. reporting entry counts).
     */
    public List<DocumentIndex> entries() { return entries; }

    /**
     * Build a registry from in-memory entries. Convenient for tests and
     * non-JSON loaders. The name-to-entry map is derived; duplicate
     * names throw {@link IllegalArgumentException}.
     */
    public static DocumentRegistry of(final Iterable<DocumentIndex> entriesIn) {
        final List<DocumentIndex> list = new ArrayList<>();
        final Map<String, Integer> nameToEntry = new HashMap<>();
        for (DocumentIndex e : entriesIn) {
            final int idx = list.size();
            if (nameToEntry.put(e.name(), idx) != null) {
                throw new IllegalArgumentException(
                        "document registry entry `" + e.name() + "`: duplicate name");
            }
            list.add(e);
        }
        return new DocumentRegistry(list, nameToEntry);
    }

    /**
     * Load a registry from the JSON shape declared in &sect;07 of the
     * design memo. An absent file is an error; empty-registry semantics
     * are what {@link #empty()} (no CLI flag) gives you, not what a
     * missing config file gives you.
     */
    public static DocumentRegistry loadFromJson(final Path path) throws IOException {
        final String text = Files.readString(path);
        final JsonObject root;
        try {
            root = JSON.parse(text);
        } catch (RuntimeException e) {
            throw new IOException(
                    "parsing document registry at " + path + ": " + e.getMessage(), e);
        }
        return fromJson(root);
    }

    /**
     * Parse a registry from an already-decoded {@link JsonObject}.
     * Extracted so unit tests can drive the parser without hitting the
     * filesystem.
     */
    public static DocumentRegistry fromJson(final JsonObject root) {
        final List<DocumentIndex> parsed = new ArrayList<>();
        if (root.hasKey("documents")) {
            final JsonValue docsVal = root.get("documents");
            if (docsVal.isArray()) {
                for (JsonValue raw : docsVal.getAsArray()) {
                    if (!raw.isObject()) continue;
                    parsed.add(parseEntry(raw.getAsObject()));
                }
            }
        }
        return of(parsed);
    }

    private static DocumentIndex parseEntry(final JsonObject raw) {
        final String name;
        if (hasNonNull(raw, "name") && raw.get("name").isString()) {
            name = raw.get("name").getAsString().value();
        } else {
            throw new IllegalArgumentException(
                    "document registry entry missing required field `name`");
        }
        if (!hasNonNull(raw, "mode")) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: missing required field `mode`");
        }
        final String modeStr = raw.get("mode").getAsString().value();
        final DocumentMode mode = switch (modeStr) {
            case "managed", "Managed", "MANAGED" -> DocumentMode.MANAGED;
            case "federated", "Federated", "FEDERATED" -> DocumentMode.FEDERATED;
            default -> throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: unknown mode `" + modeStr
                            + "` (expected `managed` or `federated`)");
        };

        final String guestUrl = requireString(raw, name, "guest_url");
        final String searchBackend = requireString(raw, name, "search_backend");
        final String storageBackend = requireString(raw, name, "storage_backend");
        final String searchIndex = requireString(raw, name, "search_index");
        final String sirixDatabase = requireString(raw, name, "sirix_database");
        final String sirixResource = requireString(raw, name, "sirix_resource");

        // Preserve opts as canonical JSON so callers pass it straight to
        // the guest without re-materializing a JsonValue. `{}` when absent.
        final String optsJson;
        if (hasNonNull(raw, "opts")) {
            optsJson = raw.get("opts").toString();
        } else {
            optsJson = "{}";
        }

        // Sweep + revision-retention are Managed-only. Federated
        // normalizes them to absent/empty so downstream code can rely
        // on the struct-level invariant without re-checking mode.
        final OptionalInt sweepInterval;
        final String revisionRetention;
        switch (mode) {
            case MANAGED -> {
                if (!hasNonNull(raw, "sweep_interval_secs")) {
                    throw new IllegalArgumentException(
                            "document registry entry `" + name + "`: managed entries must "
                                    + "declare `sweep_interval_secs`");
                }
                sweepInterval = OptionalInt.of(
                        raw.get("sweep_interval_secs").getAsNumber().value().intValue());
                if (!hasNonNull(raw, "revision_retention")) {
                    throw new IllegalArgumentException(
                            "document registry entry `" + name + "`: managed entries must "
                                    + "declare `revision_retention`");
                }
                final String retention = raw.get("revision_retention").getAsString().value();
                // v1.0 lifts the v0.2 gate on `all` — time-travel search
                // requires the sweep to mirror historical revisions into
                // the search backend, and the guest/sweep now support it.
                if (!"latest".equals(retention) && !"all".equals(retention)) {
                    throw new IllegalArgumentException(
                            "document registry entry `" + name + "`: unknown revision_retention `"
                                    + retention + "` (expected `latest` or `all`)");
                }
                revisionRetention = retention;
            }
            case FEDERATED -> {
                sweepInterval = OptionalInt.empty();
                revisionRetention = "";
            }
            default -> throw new AssertionError("unreachable");
        }

        return new DocumentIndex(name, mode, guestUrl, searchBackend, storageBackend,
                searchIndex, sirixDatabase, sirixResource, optsJson,
                sweepInterval, revisionRetention);
    }

    private static String requireString(final JsonObject raw, final String name, final String key) {
        if (!hasNonNull(raw, key) || !raw.get(key).isString()) {
            throw new IllegalArgumentException(
                    "document registry entry `" + name + "`: missing required field `" + key + "`");
        }
        return raw.get(key).getAsString().value();
    }

    /** Presence-and-non-null helper &mdash; atlas.json has no built-in equivalent of Jackson's {@code hasNonNull}. */
    private static boolean hasNonNull(final JsonObject obj, final String key) {
        if (!obj.hasKey(key)) return false;
        final JsonValue v = obj.get(key);
        return v != null && !v.isNull();
    }

    /**
     * One registered document index. Mirrors the JSON shape declared in
     * &sect;07 of the design memo. Combines the search backend
     * (Manticore) with the storage backend (SirixDB) for one composed
     * capability.
     */
    public static final class DocumentIndex {
        private final String name;
        private final DocumentMode mode;
        private final String guestUrl;
        private final String searchBackend;
        private final String storageBackend;
        private final String searchIndex;
        private final String sirixDatabase;
        private final String sirixResource;
        private final String optsJson;
        private final OptionalInt sweepIntervalSecs;
        private final String revisionRetention;

        public DocumentIndex(final String name,
                             final DocumentMode mode,
                             final String guestUrl,
                             final String searchBackend,
                             final String storageBackend,
                             final String searchIndex,
                             final String sirixDatabase,
                             final String sirixResource,
                             final String optsJson,
                             final OptionalInt sweepIntervalSecs,
                             final String revisionRetention) {
            this.name = name;
            this.mode = mode;
            this.guestUrl = guestUrl;
            this.searchBackend = searchBackend;
            this.storageBackend = storageBackend;
            this.searchIndex = searchIndex;
            this.sirixDatabase = sirixDatabase;
            this.sirixResource = sirixResource;
            this.optsJson = optsJson;
            this.sweepIntervalSecs = sweepIntervalSecs;
            this.revisionRetention = revisionRetention;
        }

        public String name()             { return name; }
        public DocumentMode mode()       { return mode; }
        /** The wasm guest URL the SERVICE dispatch resolves to. */
        public String guestUrl()         { return guestUrl; }
        /** Search backend URL (e.g. Manticore) &mdash; the guest's first backend arg. */
        public String searchBackend()    { return searchBackend; }
        /** Storage backend URL (e.g. sirix-sql-server) &mdash; the guest's second backend arg. */
        public String storageBackend()   { return storageBackend; }
        /** Search-index name inside the search backend. */
        public String searchIndex()      { return searchIndex; }
        /** Sirix database that holds the corpus. */
        public String sirixDatabase()    { return sirixDatabase; }
        /** Sirix resource within {@link #sirixDatabase()} that holds the corpus. */
        public String sirixResource()    { return sirixResource; }
        /** Raw JSON string of the {@code opts} object; passed through verbatim to the guest. */
        public String optsJson()         { return optsJson; }
        /** {@link OptionalInt#empty()} on federated entries; always present on managed. */
        public OptionalInt sweepIntervalSecs() { return sweepIntervalSecs; }
        /** Empty string on federated entries; {@code "latest"} or {@code "all"} on managed. */
        public String revisionRetention() { return revisionRetention; }
    }
}
