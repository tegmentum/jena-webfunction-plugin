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
import ai.tegmentum.wasmtime4j.wit.WitValue;
import ai.tegmentum.wasmtime4j.wit.WitVariant;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

import java.util.ArrayList;
import java.util.Arrays;
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

            boolean docUsedSubject = false;
            if (fields.get("doc") instanceof WitString) {
                final String docStr = ((WitString) fields.get("doc")).getValue();
                final String[] picked = pickDoc(docStr, innerFields);
                docUsedSubject = "1".equals(picked[1]);
                row.put("doc", scalarToNode(picked[0]));
                varSet.add("doc");
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
                final String s = flattenOptionalString(e.getValue());
                if (s == null) continue;
                row.put(e.getKey(), scalarToNode(s));
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

    private static String flattenOptionalString(final WitValue v) {
        if (v instanceof WitString) return ((WitString) v).getValue();
        if (v instanceof WitOption) {
            final Optional<WitValue> inner = ((WitOption) v).getValue();
            if (inner.isPresent() && inner.get() instanceof WitString) {
                return ((WitString) inner.get()).getValue();
            }
            return null;
        }
        return null;
    }
}
