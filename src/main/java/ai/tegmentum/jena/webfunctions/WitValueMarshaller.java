package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitEnum;
import ai.tegmentum.wasmtime4j.wit.WitFloat64;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
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
     */
    public static List<Row> bindingSetsFromWit(final WitValue witValue) {
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
}
