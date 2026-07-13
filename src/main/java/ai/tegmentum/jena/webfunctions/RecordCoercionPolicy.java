package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentType;
import org.apache.jena.atlas.json.JsonValue;

/**
 * Bare-arg record-coercion policy shared by
 * {@link JenaWasmInstance#jsonToWit}. Pulled out into a testable pure
 * helper — the record-placement decision is a function of the JSON
 * scalar's shape and the record's non-optional fields' component
 * types, and unit tests can drive it directly without spinning up a
 * component instance.
 *
 * <p>The policy is documented on {@link JenaWasmInstance}'s RECORD
 * branch and mirrors {@code oxigraph-wf/src/wf_call.rs::json_to_val}
 * so both engines coerce identical callsites into identical records.
 * A divergence would silently score-skew the
 * {@code fulltext_document_corpus.toml} conformance case.
 */
final class RecordCoercionPolicy {

    private RecordCoercionPolicy() {}

    /**
     * Structural shape of a component-model field type for the purposes
     * of bare-arg placement into a record's non-optional fields. Only
     * the surface exercised by the bare-arg path is enumerated —
     * exotic targets (RECORD / TUPLE / VARIANT / RESULT / ENUM /
     * FLAGS) collapse to {@link #OTHER} so {@link #shapeAccepts}
     * returns false and the coercion falls through with a "no matching
     * field" error rather than silently slotting a bare int into a
     * nested record.
     */
    enum FieldShape { BOOL, INT, FLOAT, STRING, LIST, OPTION, OTHER }

    /**
     * Structural shape of a JSON scalar for bare-arg placement. Numbers
     * do not distinguish int vs float — a JSON {@code 10} accepts both
     * {@link FieldShape#INT} and {@link FieldShape#FLOAT} field slots.
     * If a record has one of each as non-optional, the placement helper
     * reports the ambiguity rather than picking one.
     */
    enum JsonShape { BOOL, NUMBER, STRING, ARRAY, OBJECT, NULL }

    static FieldShape fieldShapeOf(final ComponentType kind) {
        switch (kind) {
            case BOOL: return FieldShape.BOOL;
            case S8: case U8: case S16: case U16:
            case S32: case U32: case S64: case U64: return FieldShape.INT;
            case F32: case F64: return FieldShape.FLOAT;
            case STRING: return FieldShape.STRING;
            case LIST: return FieldShape.LIST;
            case OPTION: return FieldShape.OPTION;
            default: return FieldShape.OTHER;
        }
    }

    static JsonShape jsonShapeOf(final JsonValue json) {
        if (json.isBoolean()) return JsonShape.BOOL;
        if (json.isNumber()) return JsonShape.NUMBER;
        if (json.isString()) return JsonShape.STRING;
        if (json.isArray()) return JsonShape.ARRAY;
        if (json.isObject()) return JsonShape.OBJECT;
        return JsonShape.NULL;
    }

    static boolean shapeAccepts(final FieldShape field, final JsonShape scalar) {
        if (field == FieldShape.BOOL && scalar == JsonShape.BOOL) return true;
        if (field == FieldShape.INT && scalar == JsonShape.NUMBER) return true;
        if (field == FieldShape.FLOAT && scalar == JsonShape.NUMBER) return true;
        if (field == FieldShape.STRING && scalar == JsonShape.STRING) return true;
        if (field == FieldShape.LIST && scalar == JsonShape.ARRAY) return true;
        return false;
    }
}
