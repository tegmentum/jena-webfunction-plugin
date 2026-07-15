package ai.tegmentum.jena.webfunctions.rewrite;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sidecar registry of shape descriptors for {@code wf-relational}
 * federation sources. Loaded from the same federation-config JSON that
 * {@link FederationRegistry} consumes but only captures the per-source
 * {@code relational} extension block (which
 * {@link FederationRegistry} deliberately drops &mdash; it only reads
 * the fields memo &sect;03 declares for source dispatch).
 *
 * <p>Design memo: {@code wf-conformance/docs/design/wf-relational.md}
 * &sect;04. The adapter renders the descriptor into the JSON under key
 * {@code relational}; the {@link WfRelationalRewrite} pass consumes it
 * to translate {@code SERVICE <wf-relational:<name>>} into a
 * {@code wf_fetch.wasm} dispatch with the Postgres sink URL and shape
 * descriptor baked into the {@code SERVICE <wf:call>} envelope.
 *
 * <h3>Design constraint</h3>
 * {@link FederationRegistry} is off-limits (parallel-agent scope fence).
 * This sibling registry reads the same file but only extracts the fields
 * {@link WfRelationalRewrite} needs. Empty file, missing file, or file
 * with no {@code wf-relational} sources &rarr; empty registry, and every
 * consumer treats empty as an unconditional no-op.
 *
 * <p>Java sibling of
 * {@code oxigraph-wf/src/wf_relational_registry.rs}.
 */
public final class WfRelationalRegistry {

    private final Map<String, RelationalEntry> entries;

    private WfRelationalRegistry(final Map<String, RelationalEntry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /**
     * Empty registry &mdash; the uninstrumented-startup state. Every
     * lookup is a no-op; {@link #isEmpty()} returns true.
     */
    public static WfRelationalRegistry empty() {
        return new WfRelationalRegistry(new HashMap<>());
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size()        { return entries.size(); }

    /**
     * Administrative lookup. {@code null} when {@code name} isn't a
     * {@code wf-relational} source (either absent from the JSON or a
     * different source type).
     */
    public RelationalEntry byName(final String name) {
        return entries.get(name);
    }

    /**
     * Load from the same JSON file the {@link FederationRegistry}
     * consumes. Only {@code wf-relational} sources with a
     * {@code relational} block are captured; every other entry is
     * silently skipped. A missing file returns an empty registry (matches
     * the empty-registry semantics of every sibling registry).
     */
    public static WfRelationalRegistry loadFromJson(final Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return empty();
        }
        final String text = Files.readString(path);
        final JsonObject root;
        try {
            root = JSON.parse(text);
        } catch (RuntimeException e) {
            throw new IOException(
                    "parsing wf-relational registry at " + path + ": " + e.getMessage(), e);
        }
        return fromJson(root);
    }

    /**
     * Parse a registry from an already-decoded {@link JsonObject}.
     * Extracted so unit tests can drive the parser without hitting the
     * filesystem. Valid JSON with no {@code wf-relational} sources yields
     * an empty registry.
     */
    public static WfRelationalRegistry fromJson(final JsonObject root) {
        final Map<String, RelationalEntry> out = new LinkedHashMap<>();
        if (root == null || !root.hasKey("sources")) {
            return new WfRelationalRegistry(out);
        }
        final JsonValue srcVal = root.get("sources");
        if (!srcVal.isArray()) {
            return new WfRelationalRegistry(out);
        }
        for (JsonValue raw : srcVal.getAsArray()) {
            if (!raw.isObject()) continue;
            final JsonObject src = raw.getAsObject();
            final String type = optionalString(src, "type");
            if (!"wf-relational".equals(type)
                    && !"wf_relational".equals(type)
                    && !"WfRelational".equals(type)) {
                continue;
            }
            final String name = optionalString(src, "name");
            final String endpoint = optionalString(src, "endpoint");
            if (name == null || endpoint == null) {
                continue;
            }
            if (!hasNonNull(src, "relational")) {
                continue;
            }
            final JsonValue rel = src.get("relational");
            if (!rel.isObject()) {
                continue;
            }
            final RelationalDescriptor descriptor = parseDescriptor(rel.getAsObject());
            if (descriptor == null) {
                continue;
            }
            out.put(name, new RelationalEntry(name, endpoint, descriptor));
        }
        return new WfRelationalRegistry(out);
    }

    private static RelationalDescriptor parseDescriptor(final JsonObject obj) {
        final String sinkKind = optionalString(obj, "sink_kind");
        final String table = optionalString(obj, "table");
        final String subjectColumn = optionalString(obj, "subject_column");
        if (sinkKind == null || table == null || subjectColumn == null) {
            return null;
        }
        final String anchorClass;
        if (hasNonNull(obj, "anchor") && obj.get("anchor").isObject()) {
            anchorClass = optionalString(obj.get("anchor").getAsObject(), "class");
        } else {
            anchorClass = null;
        }
        final List<Column> columns = new ArrayList<>();
        if (obj.hasKey("columns") && obj.get("columns").isArray()) {
            final JsonArray colArr = obj.get("columns").getAsArray();
            for (JsonValue cv : colArr) {
                if (!cv.isObject()) continue;
                final JsonObject co = cv.getAsObject();
                final String colName = optionalString(co, "name");
                final String role = optionalString(co, "role");
                final String predicate = optionalString(co, "predicate");
                final String xsdType = optionalString(co, "type");
                if (colName == null || role == null) continue;
                columns.add(new Column(colName, role, predicate, xsdType));
            }
        }
        final String iriTemplate = optionalString(obj, "iri_template");
        final boolean emitProvenance = optionalBool(obj, "emit_provenance", false);
        final String schemaVersion = optionalString(obj, "schema_version");
        return new RelationalDescriptor(sinkKind, table, subjectColumn,
                anchorClass, columns, iriTemplate, emitProvenance, schemaVersion);
    }

    private static String optionalString(final JsonObject obj, final String field) {
        if (obj == null || !obj.hasKey(field)) return null;
        final JsonValue v = obj.get(field);
        if (v == null || v.isNull()) return null;
        return v.isString() ? v.getAsString().value() : null;
    }

    private static boolean optionalBool(final JsonObject obj, final String field, final boolean fallback) {
        if (obj == null || !obj.hasKey(field)) return fallback;
        final JsonValue v = obj.get(field);
        if (v == null || v.isNull() || !v.isBoolean()) return fallback;
        return v.getAsBoolean().value();
    }

    private static boolean hasNonNull(final JsonObject obj, final String key) {
        if (!obj.hasKey(key)) return false;
        final JsonValue v = obj.get(key);
        return v != null && !v.isNull();
    }

    // ---------------------------------------------------------------------
    // Entry / descriptor
    // ---------------------------------------------------------------------

    /**
     * One entry captured from a {@code wf-relational} federation source.
     * {@link #descriptor()} is the wf_fetch-compatible shape descriptor
     * the adapter emits (already carries {@code sink_kind = "postgres"});
     * {@link #endpoint()} is the FederationSource's Postgres URL,
     * propagated verbatim so the rewrite pass can bake
     * {@code sink} = {@code <endpoint>#<table>} into the descriptor at
     * fold time.
     */
    public static final class RelationalEntry {
        private final String name;
        private final String endpoint;
        private final RelationalDescriptor descriptor;

        public RelationalEntry(final String name,
                               final String endpoint,
                               final RelationalDescriptor descriptor) {
            this.name = name;
            this.endpoint = endpoint;
            this.descriptor = descriptor;
        }

        public String name()                       { return name; }
        /**
         * Postgres endpoint URL ({@code postgres://user:pw@host:port/db}).
         * Comes from the FederationSource entry's {@code endpoint} field.
         */
        public String endpoint()                   { return endpoint; }
        /**
         * {@code wf-relational.md} &sect;04 shape descriptor as the
         * adapter renders it &mdash; {@code sink_kind}, {@code table},
         * {@code subject_column}, {@code anchor}, {@code columns},
         * {@code iri_template}, {@code emit_provenance},
         * {@code schema_version}. Consumed by the rewrite pass to build
         * the {@code wf:arg} descriptor literal.
         */
        public RelationalDescriptor descriptor()   { return descriptor; }
    }

    /**
     * Parsed shape descriptor block. Mirrors the JSON shape the adapter
     * emits (see {@code render_relational_descriptor} in the conformance
     * runner). Kept as a proper type (not an opaque JSON string) so the
     * rewrite pass gets typed access to
     * {@link #columnsByPredicate()} for the BGP fold.
     */
    public static final class RelationalDescriptor {
        private final String sinkKind;
        private final String table;
        private final String subjectColumn;
        private final String anchorClass;
        private final List<Column> columns;
        private final String iriTemplate;
        private final boolean emitProvenance;
        private final String schemaVersion;

        public RelationalDescriptor(final String sinkKind,
                                    final String table,
                                    final String subjectColumn,
                                    final String anchorClass,
                                    final List<Column> columns,
                                    final String iriTemplate,
                                    final boolean emitProvenance,
                                    final String schemaVersion) {
            this.sinkKind = sinkKind;
            this.table = table;
            this.subjectColumn = subjectColumn;
            this.anchorClass = anchorClass;
            this.columns = List.copyOf(columns);
            this.iriTemplate = iriTemplate;
            this.emitProvenance = emitProvenance;
            this.schemaVersion = schemaVersion;
        }

        public String sinkKind()                { return sinkKind; }
        public String table()                   { return table; }
        public String subjectColumn()           { return subjectColumn; }
        public Optional<String> anchorClass()   { return Optional.ofNullable(anchorClass); }
        public List<Column> columns()           { return columns; }
        public Optional<String> iriTemplate()   { return Optional.ofNullable(iriTemplate); }
        public boolean emitProvenance()         { return emitProvenance; }
        public Optional<String> schemaVersion() { return Optional.ofNullable(schemaVersion); }

        /**
         * Predicate IRI &rarr; column name lookup for the rewrite pass.
         * Skips the {@code subject_iri} role (its column carries the
         * subject binding, not a column predicate).
         */
        public Map<String, String> columnsByPredicate() {
            final Map<String, String> out = new LinkedHashMap<>();
            for (Column c : columns) {
                if ("subject_iri".equals(c.role())) continue;
                if (c.predicate() != null) {
                    out.put(c.predicate(), c.name());
                }
            }
            return out;
        }
    }

    /**
     * One column in the descriptor. {@code predicate} is absent for the
     * {@code subject_iri} column; {@code xsdType} is optional.
     */
    public static final class Column {
        private final String name;
        private final String role;
        private final String predicate;
        private final String xsdType;

        public Column(final String name,
                      final String role,
                      final String predicate,
                      final String xsdType) {
            this.name = name;
            this.role = role;
            this.predicate = predicate;
            this.xsdType = xsdType;
        }

        public String name()      { return name; }
        public String role()      { return role; }
        public String predicate() { return predicate; }
        public String xsdType()   { return xsdType; }
    }
}
