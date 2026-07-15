package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitEnum;
import ai.tegmentum.wasmtime4j.wit.WitFloat32;
import ai.tegmentum.wasmtime4j.wit.WitFloat64;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitS32;
import ai.tegmentum.wasmtime4j.wit.WitS64;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitTuple;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitU32;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import ai.tegmentum.wasmtime4j.wit.WitU8;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import ai.tegmentum.wasmtime4j.wit.WitVariant;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Marshalling between Jena {@link Node} and the WIT value model declared in
 * {@code src/main/wit/webfunction.wit}. The WIT world is provider-neutral (the
 * package name is {@code stardog:webfunction@0.2.0} for historical reasons —
 * components compiled against it run on both the Stardog and Jena bindings).
 */
public final class WitValueMarshaller {

    static final WitType LITERAL_TYPE;
    static final WitType VALUE_TYPE;
    static final WitType BINDING_TYPE;
    static final WitType BINDING_SETS_TYPE;
    static final WitType ACCURACY_TYPE;
    static final WitType CARDINALITY_TYPE;

    static {
        final Map<String, WitType> literalFields = new LinkedHashMap<>();
        literalFields.put("label", WitType.createString());
        literalFields.put("datatype", WitType.createString());
        literalFields.put("lang", WitType.option(WitType.createString()));
        LITERAL_TYPE = WitType.record("literal", literalFields);

        final Map<String, Optional<WitType>> valueCases = new LinkedHashMap<>();
        valueCases.put("iri", Optional.of(WitType.createString()));
        valueCases.put("literal", Optional.of(LITERAL_TYPE));
        valueCases.put("bnode", Optional.of(WitType.createString()));
        VALUE_TYPE = WitType.variant("value", valueCases);

        final Map<String, WitType> bindingFields = new LinkedHashMap<>();
        bindingFields.put("name", WitType.createString());
        bindingFields.put("value", VALUE_TYPE);
        BINDING_TYPE = WitType.record("binding", bindingFields);

        final Map<String, WitType> bindingSetsFields = new LinkedHashMap<>();
        bindingSetsFields.put("vars", WitType.list(WitType.createString()));
        bindingSetsFields.put("rows", WitType.list(WitType.list(BINDING_TYPE)));
        BINDING_SETS_TYPE = WitType.record("binding-sets", bindingSetsFields);

        ACCURACY_TYPE = WitType.enumType("accuracy", Arrays.asList(
                "verified", "injected", "accurate", "mostly-accurate",
                "probably-accurate", "possibly-off", "probably-off", "random"));

        final Map<String, WitType> cardinalityFields = new LinkedHashMap<>();
        cardinalityFields.put("value", WitType.createFloat64());
        cardinalityFields.put("accuracy", ACCURACY_TYPE);
        CARDINALITY_TYPE = WitType.record("cardinality", cardinalityFields);
    }

    private WitValueMarshaller() {}

    // ---- Jena → WIT ---------------------------------------------------------

    public static WitValue toWit(final Node node) {
        if (node.isURI()) {
            return WitVariant.of(VALUE_TYPE, "iri", witString(node.getURI()));
        }
        if (node.isBlank()) {
            return WitVariant.of(VALUE_TYPE, "bnode", witString(node.getBlankNodeLabel()));
        }
        if (node.isLiteral()) {
            return WitVariant.of(VALUE_TYPE, "literal", literalToWit(node));
        }
        throw new IllegalArgumentException("Unsupported Node kind: " + node);
    }

    private static WitRecord literalToWit(final Node node) {
        final WitType optionStringType = WitType.option(WitType.createString());
        final String lang = node.getLiteralLanguage();
        final String datatype = node.getLiteralDatatypeURI();
        return WitRecord.builder()
                .field("label", witString(node.getLiteralLexicalForm()))
                .field("datatype", witString(datatype != null && !datatype.isEmpty()
                        ? datatype
                        : XSDDatatype.XSDstring.getURI()))
                .field("lang", (lang == null || lang.isEmpty())
                        ? WitOption.none(optionStringType)
                        : WitOption.some(optionStringType, witString(lang)))
                .build();
    }

    public static WitList toWitArgs(final Node[] args) {
        if (args.length == 0) return WitList.empty(VALUE_TYPE);
        final List<WitValue> elems = new ArrayList<>(args.length);
        for (Node n : args) elems.add(toWit(n));
        return WitList.of(elems);
    }

    private static WitString witString(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new IllegalArgumentException("invalid UTF-8 string for WIT: " + s, e);
        }
    }

    // ---- WIT → Jena ---------------------------------------------------------

    public static Node valueFromWit(final WitValue witValue) {
        final WitVariant variant = (WitVariant) witValue;
        switch (variant.getCaseName()) {
            case "iri":
                return NodeFactory.createURI(
                        ((WitString) variant.getPayload()
                                .orElseThrow(() -> missingPayload("iri"))).getValue());
            case "bnode":
                return NodeFactory.createBlankNode(
                        ((WitString) variant.getPayload()
                                .orElseThrow(() -> missingPayload("bnode"))).getValue());
            case "literal":
                return literalFromWit((WitRecord) variant.getPayload()
                        .orElseThrow(() -> missingPayload("literal")));
            default:
                throw new IllegalArgumentException("Unknown value case: " + variant.getCaseName());
        }
    }

    private static IllegalArgumentException missingPayload(final String kase) {
        return new IllegalArgumentException("value variant '" + kase + "' is missing payload");
    }

    private static Node literalFromWit(final WitRecord record) {
        final String label = ((WitString) record.getField("label")).getValue();
        final String datatype = ((WitString) record.getField("datatype")).getValue();
        final Optional<Object> lang = ((WitOption) record.getField("lang")).toJava();
        if (lang.isPresent()) {
            return NodeFactory.createLiteralLang(label, (String) lang.get());
        }
        return NodeFactory.createLiteralDT(
                label,
                org.apache.jena.datatypes.TypeMapper.getInstance().getSafeTypeByName(datatype));
    }

    /**
     * A row of the WIT {@code binding-sets} record — vars in declared order,
     * corresponding node values (or null if the variable is unbound in this row).
     */
    public static final class Row {
        public final List<String> vars;
        public final List<Node> values;
        Row(final List<String> vars, final List<Node> values) {
            this.vars = vars;
            this.values = values;
        }
    }

    /**
     * Unmarshal a WIT {@code binding-sets} value into a list of Rows.
     * Every row uses the same {@code vars} list order.
     *
     * <p>Domain guests (wf_fulltext, wf_document) declare their own WIT
     * world and return {@code list<hit>} rather than the substrate's
     * {@code binding-sets { vars, rows }} record. When the guest hands
     * us a WitList we coerce hit-records into binding-sets at decode
     * time; see {@link #hitListToRows(WitList)} for the projection
     * rules.
     */
    public static List<Row> bindingSetsFromWit(final WitValue witValue) {
        if (witValue instanceof WitList) {
            return hitListToRows((WitList) witValue);
        }
        final WitRecord record = (WitRecord) witValue;
        final List<String> vars = new ArrayList<>();
        for (WitValue v : ((WitList) record.getField("vars")).getElements()) {
            vars.add(((WitString) v).getValue());
        }
        final List<Row> rows = new ArrayList<>();
        for (WitValue rowVal : ((WitList) record.getField("rows")).getElements()) {
            final List<Node> byName = new ArrayList<>(java.util.Collections.nCopies(vars.size(), null));
            for (WitValue bindingVal : ((WitList) rowVal).getElements()) {
                final WitRecord binding = (WitRecord) bindingVal;
                final String name = ((WitString) binding.getField("name")).getValue();
                final Node value = valueFromWit(binding.getField("value"));
                final int idx = vars.indexOf(name);
                if (idx >= 0) byName.set(idx, value);
            }
            rows.add(new Row(vars, byName));
        }
        return rows;
    }

    /**
     * Coerce a {@code list<hit>} value into binding-sets Rows.
     *
     * <p>Projection rules mirror
     * {@code oxigraph-wf/src/wf_call.rs::decode_hit_list}:
     * <ul>
     *   <li>Each hit becomes one row.</li>
     *   <li>Top-level scalar fields (doc, score, snippet, lang, ...)
     *       become columns of the same name.</li>
     *   <li>{@code hit.fields[k]} tuples become their own columns.</li>
     *   <li>URI-shaped strings project as URI nodes; otherwise as
     *       {@code xsd:string} literals.</li>
     *   <li>The {@code doc} column prefers a URI-shaped
     *       {@code subject} sidecar over {@code hit.doc} when the
     *       top-level value isn't URI-shaped (Manticore's numeric
     *       {@code _id} lands in {@code hit.doc}; the semantic doc URI
     *       lives in {@code _source.subject}).</li>
     *   <li>Option-typed fields drop when None.</li>
     *   <li>Numeric score projects as {@code xsd:double}.</li>
     * </ul>
     * See wf-conformance/docs/design/wf-fulltext.md §11 for the guest
     * hit shape this decoder mirrors.
     */
    private static List<Row> hitListToRows(final WitList hits) {
        final TreeSet<String> varSet = new TreeSet<>();
        final List<LinkedHashMap<String, Node>> perRow = new ArrayList<>();

        for (WitValue hitVal : hits.getElements()) {
            if (!(hitVal instanceof WitRecord)) continue;
            final WitRecord hit = (WitRecord) hitVal;
            final Map<String, WitValue> fields = hit.getFields();

            final List<Map.Entry<String, String>> innerFields = new ArrayList<>();
            if (fields.get("fields") instanceof WitList) {
                for (WitValue item : ((WitList) fields.get("fields")).getElements()) {
                    final Map.Entry<String, String> pair = stringTuple(item);
                    if (pair != null) innerFields.add(pair);
                }
            }

            final LinkedHashMap<String, Node> row = new LinkedHashMap<>();

            // `hit.doc` shape: either a plain `string` (legacy
            // wf_fulltext) or a `doc-ref { id: string,
            // revision: option<u64> }` record (wf_document v1.0+).
            // The record shape carries a bitemporal `revision` signal
            // that we surface as `?revision` (xsd:integer) when Some.
            // Legacy string shape stays wire-compatible. Mirrors
            // `oxigraph-wf/src/wf_call.rs::flatten_hit_doc`.
            boolean docUsedSubject = false;
            final DocRefFlat flat = flattenHitDoc(fields.get("doc"));
            if (flat != null) {
                final String[] picked = pickDoc(flat.id, innerFields);
                docUsedSubject = "1".equals(picked[1]);
                row.put("doc", scalarToNode(picked[0]));
                varSet.add("doc");
                if (flat.revision != null) {
                    row.put("revision", NodeFactory.createLiteralDT(
                            flat.revision.toString(), XSDDatatype.XSDinteger));
                    varSet.add("revision");
                }
            }

            final Double score = numericField(fields.get("score"));
            if (score != null) {
                row.put("score", NodeFactory.createLiteralDT(
                        score.toString(), XSDDatatype.XSDdouble));
                varSet.add("score");
            }

            for (Map.Entry<String, WitValue> e : fields.entrySet()) {
                if (e.getKey().equals("doc")
                        || e.getKey().equals("score")
                        || e.getKey().equals("fields")) continue;
                final TypedScalar ts = flattenOptionalStringOrBytes(e.getValue());
                if (ts == null) continue;
                row.put(e.getKey(), typedScalarToNode(ts));
                varSet.add(e.getKey());
            }

            for (Map.Entry<String, String> f : innerFields) {
                if (f.getKey().equals("subject") && docUsedSubject) continue;
                row.put(f.getKey(), scalarToNode(f.getValue()));
                varSet.add(f.getKey());
            }

            perRow.add(row);
        }

        final List<String> vars = new ArrayList<>(varSet);
        final List<Row> rows = new ArrayList<>(perRow.size());
        for (LinkedHashMap<String, Node> src : perRow) {
            final List<Node> byIndex = new ArrayList<>(
                    java.util.Collections.nCopies(vars.size(), null));
            for (Map.Entry<String, Node> e : src.entrySet()) {
                final int idx = vars.indexOf(e.getKey());
                if (idx >= 0) byIndex.set(idx, e.getValue());
            }
            rows.add(new Row(vars, byIndex));
        }
        return rows;
    }

    /**
     * Prefer a URI-shaped {@code subject} from {@code hit.fields} over
     * a non-URI {@code hit.doc}. Returns {@code {chosen, "1"}} when the
     * subject sidecar wins, {@code {top, "0"}} otherwise. Encoding the
     * boolean as a string keeps the method signature array-shaped so
     * the caller doesn't need a two-field struct.
     */
    private static String[] pickDoc(
            final String topDoc,
            final List<Map.Entry<String, String>> innerFields) {
        if (isUriShaped(topDoc)) return new String[] { topDoc, "0" };
        for (Map.Entry<String, String> e : innerFields) {
            if (e.getKey().equals("subject") && isUriShaped(e.getValue())) {
                return new String[] { e.getValue(), "1" };
            }
        }
        return new String[] { topDoc, "0" };
    }

    private static boolean isUriShaped(final String s) {
        return s.startsWith("http://")
                || s.startsWith("https://")
                || s.startsWith("urn:")
                || s.startsWith("file://")
                || s.startsWith("ipfs://")
                || s.startsWith("ipns://")
                || s.startsWith("sirix://");
    }

    private static Node scalarToNode(final String s) {
        if (isUriShaped(s)) return NodeFactory.createURI(s);
        return NodeFactory.createLiteralDT(s, XSDDatatype.XSDstring);
    }

    private static Map.Entry<String, String> stringTuple(final WitValue v) {
        if (!(v instanceof WitTuple)) return null;
        final List<WitValue> elems = ((WitTuple) v).getElements();
        if (elems.size() != 2) return null;
        if (!(elems.get(0) instanceof WitString)) return null;
        if (!(elems.get(1) instanceof WitString)) return null;
        return new java.util.AbstractMap.SimpleEntry<>(
                ((WitString) elems.get(0)).getValue(),
                ((WitString) elems.get(1)).getValue());
    }

    private static Double numericField(final WitValue v) {
        if (v instanceof WitFloat64) return ((WitFloat64) v).getValue();
        if (v instanceof WitFloat32) return (double) ((WitFloat32) v).getValue();
        if (v instanceof WitS64) return (double) ((WitS64) v).getValue();
        if (v instanceof WitU64) return (double) ((WitU64) v).getValue();
        if (v instanceof WitS32) return (double) ((WitS32) v).getValue();
        if (v instanceof WitU32) return (double) ((WitU32) v).getValue();
        return null;
    }

    /**
     * Flattened view of {@code hit.doc}: the {@code id} string plus an
     * optional {@code revision} (Long — WIT {@code option<u64>}).
     * {@code revision} is {@code null} when the guest left it {@code None}.
     */
    static final class DocRefFlat {
        final String id;
        final Long revision;
        DocRefFlat(final String id, final Long revision) {
            this.id = id;
            this.revision = revision;
        }
    }

    /**
     * Flatten {@code hit.doc} into an {@link DocRefFlat}.
     *
     * <p>Accepts two shapes:
     * <ul>
     *   <li>{@link WitString} — legacy wf_fulltext hit, doc is a plain
     *       string. {@code revision} is {@code null}.</li>
     *   <li>{@link WitRecord} with {@code id: string} (required) and
     *       {@code revision: option<u64>} (optional) — wf_document
     *       v1.0+ {@code doc-ref} shape. Extra fields are ignored
     *       (forward-compat).</li>
     * </ul>
     * Returns {@code null} when {@code doc} is missing or an
     * unrecognised shape; a missing {@code id} on a record throws.
     * Mirrors {@code oxigraph-wf/src/wf_call.rs::flatten_hit_doc}.
     */
    static DocRefFlat flattenHitDoc(final WitValue doc) {
        if (doc instanceof WitString) {
            return new DocRefFlat(((WitString) doc).getValue(), null);
        }
        if (doc instanceof WitRecord) {
            final Map<String, WitValue> fields = ((WitRecord) doc).getFields();
            final WitValue idVal = fields.get("id");
            if (!(idVal instanceof WitString)) {
                throw new IllegalArgumentException(
                        "hit.doc record missing `id` field or wrong type: " + idVal);
            }
            final String id = ((WitString) idVal).getValue();
            Long revision = null;
            final WitValue revVal = fields.get("revision");
            if (revVal instanceof WitOption) {
                final Optional<WitValue> inner = ((WitOption) revVal).getValue();
                if (inner.isPresent()) {
                    final WitValue rv = inner.get();
                    if (rv instanceof WitU64) revision = ((WitU64) rv).getValue();
                    else if (rv instanceof WitS64) {
                        final long n = ((WitS64) rv).getValue();
                        if (n < 0) throw new IllegalArgumentException(
                                "hit.doc.revision negative: " + n);
                        revision = n;
                    } else if (rv instanceof WitU32) {
                        revision = (long) ((WitU32) rv).getValue();
                    } else if (rv instanceof WitS32) {
                        final int n = ((WitS32) rv).getValue();
                        if (n < 0) throw new IllegalArgumentException(
                                "hit.doc.revision negative: " + n);
                        revision = (long) n;
                    } else {
                        throw new IllegalArgumentException(
                                "hit.doc.revision inner not a u64: " + rv);
                    }
                }
            }
            return new DocRefFlat(id, revision);
        }
        return null;
    }

    /**
     * Result of {@link #flattenOptionalStringOrBytes(WitValue)} —
     * either UTF-8 text (routed through {@link #scalarToNode(String)}
     * so URI-shaped strings still lift to IRI nodes) or opaque bytes
     * (rendered as an {@code xsd:base64Binary} literal).
     *
     * <p>Mirrors {@code oxigraph-wf::wf_call::TypedScalar} field-for-
     * field so the four engines project identical bindings on the
     * same guest output.
     */
    static final class TypedScalar {
        static final int TEXT = 0;
        static final int BYTES = 1;
        final int kind;
        final String text;
        final byte[] bytes;

        private TypedScalar(final int kind, final String text, final byte[] bytes) {
            this.kind = kind;
            this.text = text;
            this.bytes = bytes;
        }

        static TypedScalar text(final String s) {
            return new TypedScalar(TEXT, s, null);
        }

        static TypedScalar bytes(final byte[] b) {
            return new TypedScalar(BYTES, null, b);
        }
    }

    /**
     * Flatten {@code option<string>} <b>or</b> {@code option<list<u8>>}
     * — the two shapes hit records use for opaque top-level scalar
     * fields.
     *
     * <p>Projection rules:
     * <ul>
     *   <li>{@link WitString} and {@code Option(Some(WitString))} → text.</li>
     *   <li>{@link WitList} of {@link WitU8} and
     *       {@code Option(Some(WitList<u8>))} → UTF-8 decode. If the
     *       bytes are valid UTF-8 the result is text; otherwise raw
     *       bytes for the caller to base64-encode.</li>
     *   <li>Everything else — including {@code Option(None)} — returns
     *       {@code null} so the caller drops the binding.</li>
     * </ul>
     *
     * <p>The UTF-8-first policy trades one strict decode per hit for
     * ergonomic SPARQL access to text bodies (the common case for
     * wf_document corpora); binary bodies still round-trip correctly
     * via base64. See
     * {@code oxigraph-wf::wf_call::flatten_optional_string_or_bytes}
     * for the shared policy rationale.
     *
     * <p>Non-list, non-string option payloads (e.g. {@code option<u32>})
     * are ignored here — those need their own projector.
     */
    static TypedScalar flattenOptionalStringOrBytes(final WitValue v) {
        if (v == null) return null;
        if (v instanceof WitString) {
            return TypedScalar.text(((WitString) v).getValue());
        }
        if (v instanceof WitOption) {
            final Optional<WitValue> inner = ((WitOption) v).getValue();
            if (inner.isEmpty()) return null;
            return flattenOptionalStringOrBytes(inner.get());
        }
        if (v instanceof WitList) {
            final byte[] bytes = collectBytes((WitList) v);
            if (bytes == null) return null;
            return classifyBytes(bytes);
        }
        return null;
    }

    /**
     * UTF-8-first classification with strict decode reporting: valid
     * UTF-8 wins so downstream SPARQL sees an {@code xsd:string};
     * failure falls back to raw bytes for the base64 path.
     *
     * <p>Uses a {@link java.nio.charset.CharsetDecoder} in REPORT mode
     * rather than {@code new String(bytes, UTF_8)} because the latter
     * silently replaces malformed sequences with U+FFFD, which would
     * lie to the caller about the datatype.
     */
    private static TypedScalar classifyBytes(final byte[] bytes) {
        try {
            final String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return TypedScalar.text(decoded);
        } catch (CharacterCodingException e) {
            return TypedScalar.bytes(bytes);
        }
    }

    /**
     * A WIT {@code list<u8>} arrives as a {@link WitList} whose every
     * element is a {@link WitU8}. If any element isn't a byte we bail
     * out ({@code null}) so the caller doesn't accidentally coerce an
     * unrelated list shape into a bytes projection.
     */
    private static byte[] collectBytes(final WitList list) {
        final List<WitValue> elems = list.getElements();
        final byte[] out = new byte[elems.size()];
        for (int i = 0; i < elems.size(); i++) {
            final WitValue e = elems.get(i);
            if (!(e instanceof WitU8)) return null;
            out[i] = ((WitU8) e).getValue();
        }
        return out;
    }

    /**
     * Render a {@link TypedScalar} as a Jena {@link Node}:
     *   * text → {@link #scalarToNode(String)} (URI-shaped strings
     *     become {@code NamedNode}, everything else {@code xsd:string});
     *   * bytes → {@code xsd:base64Binary} literal.
     */
    private static Node typedScalarToNode(final TypedScalar ts) {
        if (ts.kind == TypedScalar.TEXT) {
            return scalarToNode(ts.text);
        }
        return NodeFactory.createLiteralDT(
                Base64.getEncoder().encodeToString(ts.bytes),
                XSDDatatype.XSDbase64Binary);
    }
}
