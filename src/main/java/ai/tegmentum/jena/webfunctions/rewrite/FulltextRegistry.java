package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Planner-side catalog of registered fulltext indexes. Two-mode
 * design, mirroring the design memo at
 * {@code wf-conformance/docs/design/wf-fulltext.md} &sect;02 and &sect;06:
 *
 * <ul>
 *   <li><b>{@link FulltextMode#LITERAL_INDEX}</b> &mdash; the substrate owns
 *       the index; the corpus is the store's own string literals on
 *       registered {@link FulltextIndex#predicates()}. Participates in
 *       the filter-fold rewrite (a separate follow-up pass) and in the
 *       periodic reconcile sweep.</li>
 *   <li><b>{@link FulltextMode#DOCUMENT_CORPUS}</b> &mdash; the substrate is a
 *       pure client; the index holds external documents (Wikipedia,
 *       PDFs, manuals) populated out-of-band. The SERVICE dispatch still
 *       needs the backend URL, but the filter-fold rewrite skips these
 *       entries and the periodic sweep never touches them.</li>
 * </ul>
 *
 * <p>Read-only after load; an empty registry (no config flag) is a
 * valid state and is what every consumer treats as an unconditional
 * no-op. Identical lifecycle to {@link ShapeRegistry}.
 *
 * <p>Java port of {@code oxigraph-wf/src/fulltext_registry.rs}.
 */
public final class FulltextRegistry {

    /**
     * Default sweep cadence when an entry omits {@code sweep_interval_secs}.
     * Matches &sect;07 of the memo ("seconds-minutes lag" for the periodic
     * sweep, 300s being the memo-cited example).
     */
    public static final int DEFAULT_SWEEP_INTERVAL_SECS = 300;

    /** Which of the two modes from &sect;02 of the memo an entry is in. */
    public enum FulltextMode {
        /**
         * Substrate owns the index &mdash; populated from the store's
         * own literals on registered predicates. Participates in
         * filter-fold and in the periodic reconcile sweep.
         */
        LITERAL_INDEX,
        /**
         * Substrate is a client &mdash; the index holds external
         * documents. The filter-fold rewrite skips these entries.
         */
        DOCUMENT_CORPUS
    }

    private final List<FulltextIndex> entries;
    /**
     * Predicate IRI &rarr; index-in-{@code entries} for O(1) filter-fold
     * lookup. Only populated from LITERAL_INDEX entries; DOCUMENT_CORPUS
     * entries are invisible to this map by construction. If two
     * literal-index entries claim the same predicate, first-write-wins
     * (matches how {@link ShapeRegistry} handles the analogous
     * collision).
     */
    private final Map<String, Integer> predicateToEntry;
    /** Name &rarr; index-in-{@code entries} for administrative lookup. */
    private final Map<String, Integer> nameToEntry;

    private FulltextRegistry(final List<FulltextIndex> entries,
                             final Map<String, Integer> predicateToEntry,
                             final Map<String, Integer> nameToEntry) {
        this.entries = List.copyOf(entries);
        this.predicateToEntry = Map.copyOf(predicateToEntry);
        this.nameToEntry = Map.copyOf(nameToEntry);
    }

    /**
     * Empty registry &mdash; the uninstrumented-startup state. Every
     * lookup is a no-op; {@link #isEmpty()} returns true.
     */
    public static FulltextRegistry empty() {
        return new FulltextRegistry(List.of(), Map.of(), Map.of());
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size()        { return entries.size(); }

    /**
     * Find the first {@code LITERAL_INDEX} entry whose registered
     * predicates include {@code iri}. Skips {@code DOCUMENT_CORPUS}
     * entries by construction: the predicate-to-entry map is only
     * populated from literal-index entries. Used by the filter-fold
     * rewrite.
     */
    public FulltextIndex findByPredicate(final String iri) {
        final Integer idx = predicateToEntry.get(iri);
        return idx == null ? null : entries.get(idx);
    }

    /**
     * Iterate every {@code LITERAL_INDEX} entry. Used by the periodic
     * reconcile sweep to enumerate what needs syncing against the store.
     */
    public List<FulltextIndex> literalIndexEntries() {
        final List<FulltextIndex> out = new ArrayList<>();
        for (FulltextIndex e : entries) {
            if (e.mode() == FulltextMode.LITERAL_INDEX) out.add(e);
        }
        return out;
    }

    /** Administrative lookup &mdash; resolve an entry by its {@code name} field. */
    public FulltextIndex byName(final String name) {
        final Integer idx = nameToEntry.get(name);
        return idx == null ? null : entries.get(idx);
    }

    /**
     * All registered entries, regardless of mode. Used by callers that
     * need the full set (e.g. reporting entry counts).
     */
    public List<FulltextIndex> entries() { return entries; }

    /**
     * Build a registry from in-memory entries. Convenient for tests and
     * non-JSON loaders. The name-to-entry and predicate-to-entry maps
     * are derived; predicate-to-entry only from LITERAL_INDEX entries.
     */
    public static FulltextRegistry of(final Iterable<FulltextIndex> entriesIn) {
        final List<FulltextIndex> list = new ArrayList<>();
        final Map<String, Integer> predicateToEntry = new HashMap<>();
        final Map<String, Integer> nameToEntry = new HashMap<>();
        for (FulltextIndex e : entriesIn) {
            final int idx = list.size();
            if (nameToEntry.put(e.name(), idx) != null) {
                throw new IllegalArgumentException(
                        "fulltext registry entry `" + e.name() + "`: duplicate name");
            }
            if (e.mode() == FulltextMode.LITERAL_INDEX) {
                for (String pred : e.predicates()) {
                    predicateToEntry.putIfAbsent(pred, idx);
                }
            }
            list.add(e);
        }
        return new FulltextRegistry(list, predicateToEntry, nameToEntry);
    }

    /**
     * Load a registry from the JSON shape declared in &sect;06 of the design
     * memo. An absent file is an error; empty-registry semantics are
     * what {@link #empty()} (no CLI flag) gives you, not what a missing
     * config file gives you.
     */
    public static FulltextRegistry loadFromJson(final Path path) throws IOException {
        final String text = Files.readString(path);
        final JsonObject root;
        try {
            root = JSON.parse(text);
        } catch (RuntimeException e) {
            throw new IOException(
                    "parsing fulltext registry at " + path + ": " + e.getMessage(), e);
        }
        return fromJson(root);
    }

    /**
     * Parse a registry from an already-decoded {@link JsonObject}.
     * Extracted so unit tests can drive the parser without hitting the
     * filesystem.
     */
    public static FulltextRegistry fromJson(final JsonObject root) {
        final List<FulltextIndex> parsed = new ArrayList<>();
        if (root.hasKey("indexes")) {
            final JsonValue idxVal = root.get("indexes");
            if (idxVal.isArray()) {
                for (JsonValue raw : idxVal.getAsArray()) {
                    if (!raw.isObject()) continue;
                    parsed.add(parseEntry(raw.getAsObject()));
                }
            }
        }
        return of(parsed);
    }

    private static FulltextIndex parseEntry(final JsonObject raw) {
        final String name;
        if (hasNonNull(raw, "name") && raw.get("name").isString()) {
            name = raw.get("name").getAsString().value();
        } else {
            throw new IllegalArgumentException(
                    "fulltext registry entry missing required field `name`");
        }
        if (!hasNonNull(raw, "mode")) {
            throw new IllegalArgumentException(
                    "fulltext registry entry `" + name + "`: missing required field `mode`");
        }
        final String modeStr = raw.get("mode").getAsString().value();
        final FulltextMode mode = switch (modeStr) {
            case "literal-index", "literal_index", "LiteralIndex" -> FulltextMode.LITERAL_INDEX;
            case "document-corpus", "document_corpus", "DocumentCorpus" -> FulltextMode.DOCUMENT_CORPUS;
            default -> throw new IllegalArgumentException(
                    "fulltext registry entry `" + name + "`: unknown mode `" + modeStr
                            + "` (expected `literal-index` or `document-corpus`)");
        };
        if (!hasNonNull(raw, "backend_url")) {
            throw new IllegalArgumentException(
                    "fulltext registry entry `" + name + "`: missing required field `backend_url`");
        }
        final String backendUrl = raw.get("backend_url").getAsString().value();

        final List<String> predicates = new ArrayList<>();
        if (raw.hasKey("predicates")) {
            final JsonValue predsNode = raw.get("predicates");
            if (predsNode.isArray()) {
                for (JsonValue p : predsNode.getAsArray()) {
                    if (p.isString()) predicates.add(p.getAsString().value());
                }
            }
        }

        switch (mode) {
            case LITERAL_INDEX -> {
                if (predicates.isEmpty()) {
                    throw new IllegalArgumentException(
                            "fulltext registry entry `" + name + "`: literal-index entries must "
                                    + "declare at least one predicate");
                }
            }
            case DOCUMENT_CORPUS -> {
                if (!predicates.isEmpty()) {
                    throw new IllegalArgumentException(
                            "fulltext registry entry `" + name + "`: document-corpus entries "
                                    + "must not declare predicates (the corpus is external to the store)");
                }
            }
        }

        // Preserve opts as canonical JSON so callers pass it straight to
        // the guest without re-materializing a JsonValue. `{}` when absent.
        final String optsJson;
        if (hasNonNull(raw, "opts")) {
            optsJson = raw.get("opts").toString();
        } else {
            optsJson = "{}";
        }

        // Languages + sweep interval are only meaningful for LITERAL_INDEX;
        // strip them on DOCUMENT_CORPUS so downstream code can rely on
        // the struct-level invariant without re-checking mode.
        final List<String> languages;
        final OptionalInt sweepInterval;
        if (mode == FulltextMode.LITERAL_INDEX) {
            languages = new ArrayList<>();
            if (raw.hasKey("languages")) {
                final JsonValue langs = raw.get("languages");
                if (langs.isArray()) {
                    for (JsonValue l : langs.getAsArray()) {
                        if (l.isString()) languages.add(l.getAsString().value());
                    }
                }
            }
            sweepInterval = hasNonNull(raw, "sweep_interval_secs")
                    ? OptionalInt.of(raw.get("sweep_interval_secs").getAsNumber().value().intValue())
                    : OptionalInt.empty();
        } else {
            languages = List.of();
            sweepInterval = OptionalInt.empty();
        }

        return new FulltextIndex(name, mode, backendUrl, predicates, optsJson, languages, sweepInterval);
    }

    /** Presence-and-non-null helper — atlas.json has no built-in equivalent of Jackson's {@code hasNonNull}. */
    private static boolean hasNonNull(final JsonObject obj, final String key) {
        if (!obj.hasKey(key)) return false;
        final JsonValue v = obj.get(key);
        return v != null && !v.isNull();
    }

    /**
     * One registered fulltext index. Mirrors the JSON shape declared in
     * &sect;06 of the design memo. Java port of the Rust
     * {@code FulltextIndex} record.
     */
    public static final class FulltextIndex {
        private final String name;
        private final FulltextMode mode;
        private final String backendUrl;
        private final List<String> predicates;
        private final String optsJson;
        private final List<String> languages;
        private final OptionalInt sweepIntervalSecs;

        public FulltextIndex(final String name,
                             final FulltextMode mode,
                             final String backendUrl,
                             final List<String> predicates,
                             final String optsJson,
                             final List<String> languages,
                             final OptionalInt sweepIntervalSecs) {
            this.name = name;
            this.mode = mode;
            this.backendUrl = backendUrl;
            this.predicates = List.copyOf(predicates);
            this.optsJson = optsJson;
            this.languages = List.copyOf(languages);
            this.sweepIntervalSecs = sweepIntervalSecs;
        }

        public String name()                     { return name; }
        public FulltextMode mode()               { return mode; }
        public String backendUrl()               { return backendUrl; }
        public List<String> predicates()         { return predicates; }
        /** Raw JSON string of the {@code opts} object; passed through verbatim to the guest. */
        public String optsJson()                 { return optsJson; }
        /** BCP-47 language tags this index covers. Only meaningful for LITERAL_INDEX. */
        public List<String> languages()          { return languages; }
        /** {@link OptionalInt#empty()} means the sweep uses its {@link #DEFAULT_SWEEP_INTERVAL_SECS default}. */
        public OptionalInt sweepIntervalSecs()   { return sweepIntervalSecs; }

        // Suppress unused-warning for the helper; keeps the code around
        // for future consumers that want to expose an ordered opts view.
        @SuppressWarnings("unused")
        Map<String, String> emptyOptsView() { return new LinkedHashMap<>(); }
    }
}
